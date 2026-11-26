package ma.nuzul.search.adapter.in.kafka;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.RetryListener;

/**
 * Makes an outage the search indexer is waiting out visible (story 3.2): the unbounded retry
 * ({@link SearchIndexerKafkaConfig}) would otherwise hold a partition silently for as long as Elasticsearch or Postgres
 * is down. On a failed delivery whose failure is an outage, logs a WARN naming the record's
 * {@code topic-partition@offset}, the delivery attempt and the outage cause's class and message; at most once per
 * partition per {@code interval}, so a long outage gives one line a minute per stuck partition rather than one per
 * back-off tick. Never the key or payload, which can carry guest data. Bounded failures are not logged here: the
 * error handler already logs them when it dead-letters.
 *
 * <p>Lives on the error handler because that is where the retry decision is made: every failed delivery passes through
 * it, whatever the listener did. A partition's entry is kept after the outage ends (nothing reports a success), so a
 * second outage within {@code interval} of the last warning is first logged once the interval has passed.
 */
class OutageRetryLog implements RetryListener {

    private static final Logger log = LoggerFactory.getLogger(OutageRetryLog.class);

    private final Clock clock;
    private final Duration interval;
    /** Last warning per partition; bounded by the partitions this instance is assigned. */
    private final Map<TopicPartition, Instant> lastWarned = new ConcurrentHashMap<>();

    OutageRetryLog(Clock clock, Duration interval) {
        this.clock = clock;
        this.interval = interval;
    }

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
        Throwable cause = SearchIndexerKafkaConfig.outageCause(ex);
        if (cause == null || !dueForWarning(new TopicPartition(record.topic(), record.partition()))) {
            return;
        }
        log.warn(
                "Search indexer waiting out an outage: {}-{}@{} failed delivery attempt {} with {}: {}; retrying"
                        + " until it passes",
                record.topic(),
                record.partition(),
                record.offset(),
                deliveryAttempt,
                cause.getClass().getName(),
                cause.getMessage());
    }

    private boolean dueForWarning(TopicPartition partition) {
        Instant now = clock.instant();
        boolean[] due = {false};
        lastWarned.compute(partition, (key, last) -> {
            if (last == null || !now.isBefore(last.plus(interval))) {
                due[0] = true;
                return now;
            }
            return last;
        });
        return due[0];
    }
}
