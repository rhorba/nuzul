package ma.nuzul.shared.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Publishes unpublished outbox rows to Kafka (ADR-4), oldest first, in one short transaction per run: the rows are
 * locked {@code FOR UPDATE SKIP LOCKED}, sent one at a time (each send acknowledged before the next, so one
 * aggregate's events reach its partition in order) and marked as they succeed. Delivery is at-least-once: a crash
 * between the broker's ack and the commit resends the row, and consumers dedupe on the {@code event-id} header.
 *
 * <p>Send failures are classified with an allowlist, because counting an attempt for a failure that is not the row's
 * fault would park healthy rows during an outage and break their aggregate's order:
 *
 * <ul>
 *   <li><b>The record's fault</b> (an aggregate no topic is known for, or {@link RecordTooLargeException},
 *       {@link SerializationException} or {@link InvalidRecordException} anywhere in the cause chain): its attempts go
 *       up with the error recorded, and the run carries on with the rows behind it, except later rows of the same
 *       aggregate, which wait so that the aggregate's order holds. At {@code max-attempts} the row is parked
 *       ({@code outbox.failed} gauge) and its aggregate moves on.
 *   <li><b>Anything else</b> (broker unreachable, no ack within the send timeout, authorization, a producer that
 *       cannot be built or has failed, ...): a systemic problem that would hit every row. The run stops and no
 *       attempt is counted, however long it lasts; the next run starts again from the same row.
 * </ul>
 *
 * Either way the run commits what it published. A database error is not caught: the run rolls back and its rows are
 * sent again next time, which consumers absorb as duplicates.
 *
 * <p>Per-aggregate order also relies on one relay at a time (the job's ShedLock lock): two concurrent runs never send
 * a row twice, but could interleave one aggregate's rows. So a run stops starting new sends once its {@code run-budget}
 * is spent, and budget plus one send timeout must fit inside the lock's lockAtMostFor: a slow broker shortens runs
 * instead of letting the lock expire under a run still in progress.
 */
@Component
class OutboxRelay {

    static final String EVENT_ID_HEADER = "event-id";
    static final String EVENT_TYPE_HEADER = "event-type";
    static final int MAX_ERROR_LENGTH = 1000;

    /** Longer than the producer's delivery.timeout.ms (10 s), so the producer reports its own timeout first. */
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);

    /** Covers max.block.ms on the last send, the batch lock and the commit (see the run-budget check). */
    static final Duration LOCK_MARGIN = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Why a run ended. */
    enum Stop {
        /** Every row of the batch was handled. */
        NONE,
        /** A failure that is not the record's fault (broker unavailable, authorization, ...). */
        KAFKA_FAILURE,
        /** The run budget was spent; the rest of the batch waits for the next run. */
        BUDGET,
        INTERRUPTED
    }

    /** What one run did. */
    record Result(int published, int failed, Stop stop) {}

    private final OutboxRelayStore store;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionOperations transaction;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration runBudget;
    private final Duration sendTimeout;
    private final LongSupplier nanoTime;

    @Autowired
    OutboxRelay(
            OutboxRelayStore store,
            KafkaTemplate<String, String> kafka,
            TransactionOperations transaction,
            @Value("${app.outbox.batch-size:100}") int batchSize,
            @Value("${app.outbox.max-attempts:10}") int maxAttempts,
            @Value("${app.outbox.run-budget:PT30S}") Duration runBudget) {
        this(store, kafka, transaction, batchSize, maxAttempts, runBudget, SEND_TIMEOUT, System::nanoTime);
    }

    OutboxRelay(
            OutboxRelayStore store,
            KafkaTemplate<String, String> kafka,
            TransactionOperations transaction,
            int batchSize,
            int maxAttempts,
            Duration runBudget,
            Duration sendTimeout,
            LongSupplier nanoTime) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.outbox.batch-size must be at least 1, was " + batchSize);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("app.outbox.max-attempts must be at least 1, was " + maxAttempts);
        }
        Duration lockAtMostFor = Duration.parse(OutboxRelayJob.LOCK_AT_MOST_FOR);
        // The last send can start just before the budget runs out and then block on metadata (max.block.ms) before
        // its own send timeout starts, and the batch lock and commit come on top: keep a margin for all of that.
        if (!runBudget.isPositive()
                || runBudget.plus(sendTimeout).plus(LOCK_MARGIN).compareTo(lockAtMostFor) >= 0) {
            throw new IllegalArgumentException("app.outbox.run-budget must be positive and, plus the " + sendTimeout
                    + " send timeout and a " + LOCK_MARGIN + " margin, shorter than the relay lock's "
                    + lockAtMostFor + "; was " + runBudget);
        }
        this.store = store;
        this.kafka = kafka;
        this.transaction = transaction;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.runBudget = runBudget;
        this.sendTimeout = sendTimeout;
        this.nanoTime = nanoTime;
    }

    Result relay() {
        long start = nanoTime.getAsLong();
        Result result = transaction.execute(status -> relayBatch(start));
        if (result.published() > 0) {
            log.info("Published {} outbox event(s)", result.published());
        }
        return result;
    }

    private Result relayBatch(long start) {
        List<OutboxRow> rows = store.lockBatch(maxAttempts, batchSize);
        Set<UUID> blockedAggregates = new HashSet<>();
        int published = 0;
        int failed = 0;
        for (OutboxRow row : rows) {
            if (blockedAggregates.contains(row.aggregateId())) {
                continue;
            }
            if (nanoTime.getAsLong() - start >= runBudget.toNanos()) {
                log.info(
                        "Outbox relay run budget {} spent after {} event(s); the next run continues",
                        runBudget,
                        published);
                return new Result(published, failed, Stop.BUDGET);
            }
            String topic;
            try {
                topic = Aggregate.fromColumn(row.aggregate()).topic();
            } catch (IllegalArgumentException e) {
                failed++;
                blockedAggregates.add(row.aggregateId());
                recordFailure(row, describe(e));
                continue;
            }
            try {
                send(row, topic);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Outbox relay interrupted after {} event(s); the next run resumes at {}", published, row.id());
                return new Result(published, failed, Stop.INTERRUPTED);
            } catch (TimeoutException e) {
                return kafkaFailure(published, failed, row, "no ack within " + sendTimeout);
            } catch (Exception e) {
                if (!isRecordFault(e)) {
                    return kafkaFailure(published, failed, row, describe(e));
                }
                failed++;
                blockedAggregates.add(row.aggregateId());
                recordFailure(row, describe(e));
                continue;
            }
            store.markPublished(row.id());
            published++;
        }
        return new Result(published, failed, Stop.NONE);
    }

    private void send(OutboxRow row, String topic) throws InterruptedException, ExecutionException, TimeoutException {
        ProducerRecord<String, String> producerRecord =
                new ProducerRecord<>(topic, row.aggregateId().toString(), row.payload());
        producerRecord.headers().add(EVENT_ID_HEADER, row.id().toString().getBytes(StandardCharsets.UTF_8));
        producerRecord.headers().add(EVENT_TYPE_HEADER, row.type().getBytes(StandardCharsets.UTF_8));
        kafka.send(producerRecord).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Result kafkaFailure(int published, int failed, OutboxRow row, String error) {
        log.warn(
                "Kafka send failed: outbox relay stopped after {} event(s), the next run retries from {}: {}",
                published,
                row.id(),
                error);
        return new Result(published, failed, Stop.KAFKA_FAILURE);
    }

    private void recordFailure(OutboxRow row, String description) {
        String error = truncate(description);
        int attempts = store.markFailed(row.id(), error);
        if (attempts >= maxAttempts) {
            log.error(
                    "Outbox event {} ({} {}) parked after {} attempt(s): {}",
                    row.id(),
                    row.aggregate(),
                    row.aggregateId(),
                    attempts,
                    error);
        } else {
            log.warn("Outbox event {} failed (attempt {} of {}): {}", row.id(), attempts, maxAttempts, error);
        }
    }

    /** Whether a send failure belongs to the record itself (anywhere in the cause chain), rather than to Kafka. */
    static boolean isRecordFault(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RecordTooLargeException
                    || t instanceof SerializationException
                    || t instanceof InvalidRecordException) {
                return true;
            }
        }
        return false;
    }

    /** The innermost cause, which names the real problem under the ExecutionException / KafkaException wrappers. */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    static String truncate(String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
