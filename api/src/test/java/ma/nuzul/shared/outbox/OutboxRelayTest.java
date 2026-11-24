package ma.nuzul.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import ma.nuzul.shared.outbox.OutboxRelay.Result;
import ma.nuzul.shared.outbox.OutboxRelay.Stop;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionOperations;

/** Story 3.1: the relay's per-row bookkeeping over an in-memory table and a mocked KafkaTemplate (Kafka is OutboxRelayIT). */
class OutboxRelayTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BUDGET = Duration.ofSeconds(30);

    private final FakeStore store = new FakeStore();
    private final List<ProducerRecord<String, String>> sent = new ArrayList<>();
    /** Decides each send's outcome by payload; null means success. */
    private Function<String, Throwable> failure = payload -> null;
    /** The relay's monotonic clock in nanoseconds; sends move it on to spend the run budget. */
    private final AtomicLong nanos = new AtomicLong();
    /** How far each send moves the clock (a slow broker). */
    private Duration sendTakes = Duration.ZERO;

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    {
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> producerRecord = invocation.getArgument(0);
            nanos.addAndGet(sendTakes.toNanos());
            Throwable error = failure.apply(producerRecord.value());
            if (error != null) {
                return CompletableFuture.failedFuture(error);
            }
            sent.add(producerRecord);
            return CompletableFuture.completedFuture(new SendResult<>(producerRecord, null));
        });
    }

    private OutboxRelay relay(int batchSize) {
        return new OutboxRelay(
                store,
                kafka,
                TransactionOperations.withoutTransaction(),
                batchSize,
                MAX_ATTEMPTS,
                BUDGET,
                Duration.ofMillis(200),
                nanos::get);
    }

    private static String header(ProducerRecord<String, String> producerRecord, String name) {
        return new String(producerRecord.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static KafkaProducerException producerFailure(Throwable cause) {
        return new KafkaProducerException(null, "Failed to send", cause);
    }

    @Test
    void publishesInOrderWithKeyHeadersAndTopicThenMarksPublished() {
        UUID reservation = UUID.randomUUID();
        UUID hotel = UUID.randomUUID();
        UUID first = store.add("reservation", reservation, "ReservationHeld", "{\"n\":1}");
        UUID second = store.add("hotel", hotel, "HotelCreated", "{\"n\":2}");

        Result result = relay(100).relay();

        assertThat(result).isEqualTo(new Result(2, 0, Stop.NONE));
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).topic()).isEqualTo(Topics.RESERVATION_EVENTS);
        assertThat(sent.get(0).key()).isEqualTo(reservation.toString());
        assertThat(sent.get(0).value()).isEqualTo("{\"n\":1}");
        assertThat(header(sent.get(0), OutboxRelay.EVENT_ID_HEADER)).isEqualTo(first.toString());
        assertThat(header(sent.get(0), OutboxRelay.EVENT_TYPE_HEADER)).isEqualTo("ReservationHeld");
        assertThat(sent.get(1).topic()).isEqualTo(Topics.CATALOG_EVENTS);
        assertThat(store.published).containsExactly(first, second);
    }

    @Test
    void batchSizeCapsOneRun() {
        for (int i = 0; i < 5; i++) {
            store.add("reservation", UUID.randomUUID(), "ReservationHeld", "{}");
        }

        assertThat(relay(2).relay().published()).isEqualTo(2);
        assertThat(store.lastBatchSize).isEqualTo(2);
        assertThat(store.lastMaxAttempts).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void brokerUnavailableStopsTheRunWithoutCountingAnAttempt() {
        UUID ok = store.add("reservation", UUID.randomUUID(), "ReservationHeld", "\"ok\"");
        UUID down = store.add("reservation", UUID.randomUUID(), "ReservationHeld", "\"down\"");
        UUID after = store.add("reservation", UUID.randomUUID(), "ReservationHeld", "\"after\"");
        failure = payload -> payload.equals("\"down\"")
                ? producerFailure(new TimeoutException("Topic reservation-events not present in metadata"))
                : null;

        Result result = relay(100).relay();

        assertThat(result).isEqualTo(new Result(1, 0, Stop.KAFKA_FAILURE));
        assertThat(store.published).containsExactly(ok);
        assertThat(store.attempts).isEmpty();
        assertThat(sent).extracting(ProducerRecord::value).containsExactly("\"ok\"");
        assertThat(store.published).doesNotContain(down, after);
    }

    @Test
    void authorizationFailureStopsTheRunWithoutCountingAnAttempt() {
        store.add("reservation", UUID.randomUUID(), "ReservationHeld", "{}");
        store.add("reservation", UUID.randomUUID(), "ReservationHeld", "{}");
        failure = payload -> producerFailure(new TopicAuthorizationException("Not authorized to access topics"));

        Result result = relay(100).relay();

        assertThat(result).isEqualTo(new Result(0, 0, Stop.KAFKA_FAILURE));
        assertThat(store.attempts).isEmpty();
    }

    @Test
    void producerThatCannotBeBuiltStopsTheRunWithoutCountingAnAttempt() {
        store.add("reservation", UUID.randomUUID(), "ReservationHeld", "{}");
        // Thrown by send() itself, before any future exists: the producer factory cannot build a producer.
        when(kafka.send(any(ProducerRecord.class)))
                .thenThrow(new org.apache.kafka.common.KafkaException(
                        "Failed to construct kafka producer", new ConfigException("No resolvable bootstrap urls")));

        Result result = relay(100).relay();

        assertThat(result).isEqualTo(new Result(0, 0, Stop.KAFKA_FAILURE));
        assertThat(store.attempts).isEmpty();
    }

    @Test
    void onlyFailuresOfTheRecordItselfAreRecordFaults() {
        assertThat(OutboxRelay.isRecordFault(
                        new ExecutionException(producerFailure(new RecordTooLargeException("big")))))
                .isTrue();
        assertThat(OutboxRelay.isRecordFault(new KafkaException("wrapped", new SerializationException("bad"))))
                .isTrue();
        assertThat(OutboxRelay.isRecordFault(new ExecutionException(new InvalidRecordException("invalid"))))
                .isTrue();
        assertThat(OutboxRelay.isRecordFault(new ExecutionException(producerFailure(new TimeoutException("down")))))
                .isFalse();
        assertThat(OutboxRelay.isRecordFault(new ClusterAuthorizationException("no")))
                .isFalse();
        assertThat(OutboxRelay.isRecordFault(new IllegalStateException("producer closed")))
                .isFalse();
    }

    @Test
    void noAckWithinTheSendTimeoutStopsTheRun() {
        store.add("reservation", UUID.randomUUID(), "ReservationHeld", "{}");
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        Result result = relay(100).relay();

        assertThat(result).isEqualTo(new Result(0, 0, Stop.KAFKA_FAILURE));
        assertThat(store.attempts).isEmpty();
    }

    @Test
    void aSpentRunBudgetStopsBeforeTheNextSendAndKeepsWhatWasPublished() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(store.add("reservation", UUID.randomUUID(), "ReservationHeld", "{}"));
        }
        sendTakes = Duration.ofSeconds(12); // a slow broker: the 30 s budget is spent after the third send

        Result result = relay(100).relay();

        assertThat(result).isEqualTo(new Result(3, 0, Stop.BUDGET));
        assertThat(store.published).containsExactlyElementsOf(ids.subList(0, 3));
        assertThat(store.attempts).isEmpty();
    }

    @Test
    void aDatabaseErrorIsNotTreatedAsARowFailure() {
        store.add("reservation", UUID.randomUUID(), "ReservationHeld", "{}");
        store.markPublishedFails = true;
        OutboxRelay relay = relay(100);

        assertThatThrownBy(relay::relay).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(store.attempts).isEmpty();
    }

    @Test
    void poisonRowCountsAnAttemptRecordsTheErrorAndTheRunContinues() {
        UUID poison = store.add("reservation", UUID.randomUUID(), "ReservationHeld", "\"poison\"");
        UUID next = store.add("reservation", UUID.randomUUID(), "ReservationHeld", "\"next\"");
        failure = payload -> payload.equals("\"poison\"")
                ? producerFailure(new RecordTooLargeException("The message is 1500000 bytes"))
                : null;

        Result result = relay(100).relay();

        assertThat(result).isEqualTo(new Result(1, 1, Stop.NONE));
        assertThat(store.attempts).containsEntry(poison, 1);
        assertThat(store.errors.get(poison)).isEqualTo("RecordTooLargeException: The message is 1500000 bytes");
        assertThat(store.published).containsExactly(next);
    }

    @Test
    void laterRowsOfThePoisonedAggregateWaitButOtherAggregatesArePublished() {
        UUID stuck = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID poison = store.add("reservation", stuck, "ReservationHeld", "\"poison\"");
        UUID stuckLater = store.add("reservation", stuck, "ReservationConfirmed", "\"later\"");
        UUID otherRow = store.add("reservation", other, "ReservationHeld", "\"other\"");
        failure = payload ->
                payload.equals("\"poison\"") ? producerFailure(new RecordTooLargeException("too big")) : null;

        Result result = relay(100).relay();

        assertThat(result).isEqualTo(new Result(1, 1, Stop.NONE));
        assertThat(store.published).containsExactly(otherRow);
        assertThat(store.attempts).containsOnlyKeys(poison);
        assertThat(store.published).doesNotContain(stuckLater);
        assertThat(sent).extracting(ProducerRecord::value).containsExactly("\"other\"");
    }

    @Test
    void unknownAggregateIsAPoisonRow() {
        UUID unknownAggregate = UUID.randomUUID();
        UUID unknown = store.add("invoice", unknownAggregate, "InvoiceIssued", "{}");
        UUID sameAggregateLater = store.add("reservation", unknownAggregate, "ReservationHeld", "{}");
        UUID next = store.add("room_type", UUID.randomUUID(), "RoomTypeCreated", "{}");

        Result result = relay(100).relay();

        assertThat(result).isEqualTo(new Result(1, 1, Stop.NONE));
        assertThat(store.errors.get(unknown)).isEqualTo("IllegalArgumentException: unknown outbox aggregate: invoice");
        assertThat(store.published).containsExactly(next).doesNotContain(sameAggregateLater);
    }

    @Test
    void rowAtMaxAttemptsIsParkedAndNoLongerRead() {
        UUID poison = store.add("reservation", UUID.randomUUID(), "ReservationHeld", "\"poison\"");
        failure = payload -> producerFailure(new RecordTooLargeException("too big"));
        OutboxRelay relay = relay(100);

        for (int i = 0; i < MAX_ATTEMPTS + 2; i++) {
            relay.relay();
        }

        assertThat(store.attempts).containsEntry(poison, MAX_ATTEMPTS);
    }

    @Test
    void longErrorsAreTruncated() {
        UUID poison = store.add("reservation", UUID.randomUUID(), "ReservationHeld", "{}");
        failure = payload -> new RecordTooLargeException("x".repeat(5000));

        relay(100).relay();

        assertThat(store.errors.get(poison)).hasSize(OutboxRelay.MAX_ERROR_LENGTH);
        assertThat(OutboxRelay.truncate("short")).isEqualTo("short");
    }

    @Test
    void interruptStopsTheRunAndKeepsTheFlag() {
        store.add("reservation", UUID.randomUUID(), "ReservationHeld", "{}");
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            Result result = relay(100).relay();
            assertThat(result.stop()).isEqualTo(Stop.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(store.attempts).isEmpty();
    }

    @Test
    void configIsValidated() {
        TransactionOperations tx = TransactionOperations.withoutTransaction();
        assertThatThrownBy(() -> new OutboxRelay(store, kafka, tx, 0, 1, BUDGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.outbox.batch-size");
        assertThatThrownBy(() -> new OutboxRelay(store, kafka, tx, 1, 0, BUDGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.outbox.max-attempts");
        assertThatThrownBy(() -> new OutboxRelay(store, kafka, tx, 1, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.outbox.run-budget");
        // 35 s plus the 15 s send timeout and the 10 s margin reaches the lock's lockAtMostFor (1 min).
        assertThatThrownBy(() -> new OutboxRelay(store, kafka, tx, 1, 1, Duration.ofSeconds(35)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.outbox.run-budget");
        assertThat(new OutboxRelay(store, kafka, tx, 1, 1, Duration.ofSeconds(34)))
                .isNotNull();
    }

    /** {@code outbox_event} in memory: rows in created_at order, the batch read honouring attempts and the limit. */
    static final class FakeStore implements OutboxRelayStore {

        final Map<UUID, OutboxRow> rows = new LinkedHashMap<>();
        final List<UUID> published = new ArrayList<>();
        final Map<UUID, Integer> attempts = new HashMap<>();
        final Map<UUID, String> errors = new HashMap<>();
        int lastBatchSize;
        int lastMaxAttempts;
        /** Fails markPublished like a database outage. */
        boolean markPublishedFails;

        UUID add(String aggregate, UUID aggregateId, String type, String payload) {
            UUID id = UUID.randomUUID();
            rows.put(id, new OutboxRow(id, aggregate, aggregateId, type, payload));
            return id;
        }

        @Override
        public List<OutboxRow> lockBatch(int maxAttempts, int batchSize) {
            lastBatchSize = batchSize;
            lastMaxAttempts = maxAttempts;
            return rows.values().stream()
                    .filter(row -> !published.contains(row.id()))
                    .filter(row -> attempts.getOrDefault(row.id(), 0) < maxAttempts)
                    .limit(batchSize)
                    .toList();
        }

        @Override
        public void markPublished(UUID id) {
            if (markPublishedFails) {
                throw new DataAccessResourceFailureException("connection lost");
            }
            published.add(id);
        }

        @Override
        public int markFailed(UUID id, String error) {
            errors.put(id, error);
            return attempts.merge(id, 1, Integer::sum);
        }

        @Override
        public double lagSeconds(int maxAttempts) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public long parkedCount(int maxAttempts) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public int deletePublishedBefore(Duration retention) {
            throw new UnsupportedOperationException("not used here");
        }
    }
}
