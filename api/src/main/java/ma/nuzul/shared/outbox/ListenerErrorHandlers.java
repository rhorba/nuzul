package ma.nuzul.shared.outbox;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;

/**
 * The standard listener error handler ({@link KafkaConsumerConfig}: bounded retries, then the DLT; bad headers and
 * payloads straight to the DLT) as a fresh instance, for a module whose listener needs its own container factory and
 * adjusts the handler (the search indexer waits out an Elasticsearch outage instead of dead-lettering).
 */
public final class ListenerErrorHandlers {

    /** The property placeholder for the topics' partition count, which the DLT partitioning depends on. */
    public static final String PARTITIONS = OutboxTopicsConfig.PARTITIONS;

    private ListenerErrorHandlers() {}

    /** A new handler, configured exactly like the shared one; each container gets its own retry state. */
    public static DefaultErrorHandler standard(KafkaTemplate<String, String> kafka, int partitions) {
        return standard(KafkaConsumerConfig.deadLetterRecoverer(kafka, partitions), KafkaConsumerConfig.backOff());
    }

    /** The same classification with a given recoverer and back-off, so tests need neither a broker nor real waits. */
    public static DefaultErrorHandler standard(ConsumerRecordRecoverer recoverer, BackOff backOff) {
        return KafkaConsumerConfig.errorHandler(recoverer, backOff);
    }
}
