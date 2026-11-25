package ma.nuzul.shared.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;

/**
 * What every {@code @KafkaListener} does when its handler throws (Boot wires this single {@link CommonErrorHandler}
 * bean into the default listener container factory): retry the record in place with exponential backoff (3 retries,
 * after 0.5 s, 1 s and 2 s), then publish it to {@code <topic>.DLT}, same partition, with its original key, value and
 * headers plus Spring's {@code kafka_dlt-*} headers, and move on. Of the exception headers only the class names are
 * kept: exception messages and stack traces are excluded, because a handler's error text (an SMTP rejection quoting
 * the recipient, a constraint violation's key detail) can carry guest PII, which never goes into Kafka. Retries hold the partition for ~3.5 s at
 * most, well inside {@code max.poll.interval.ms}.
 *
 * <p>Only failures of the record itself skip the retries: a missing or malformed event header
 * ({@link InvalidEventHeaderException}), a payload the listener cannot read ({@link InvalidEventPayloadException}),
 * plus Spring Kafka's defaults (deserialization, conversion, ...). The classification walks the whole cause chain, so
 * broad types are deliberately not listed: a {@code JacksonException} or IllegalArgumentException thrown by a handler
 * (an HTTP client parsing a provider's error response, say) may well be transient and keeps its retries. Listeners
 * wrap their own payload parsing in {@link InvalidEventPayloadException} instead.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfig {

    static final int MAX_RETRIES = 3;
    static final long INITIAL_INTERVAL_MS = 500;
    static final double MULTIPLIER = 2.0;
    static final long MAX_INTERVAL_MS = 5_000;

    /** Lets Kafka's partitioner pick the DLT partition. */
    static final int ANY_PARTITION = -1;

    @Bean
    CommonErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> kafka, @Value(OutboxTopicsConfig.PARTITIONS) int partitions) {
        return errorHandler(deadLetterRecoverer(kafka, partitions), backOff());
    }

    static DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<String, String> kafka, int partitions) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafka, (failed, exception) -> deadLetterPartition(failed, partitions));
        recoverer.excludeHeader(HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE);
        return recoverer;
    }

    static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer, BackOff backOff) {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventHeaderException.class, InvalidEventPayloadException.class);
        return handler;
    }

    static ExponentialBackOffWithMaxRetries backOff() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);
        return backOff;
    }

    /**
     * Same partition on the DLT: a topic and its DLT are both declared with {@code app.kafka.partitions}
     * ({@link OutboxTopicsConfig}) and must be resized together. A record from a partition the DLT is not declared to
     * have (the topic grew outside this setting) goes to any DLT partition instead of to a partition that may not
     * exist, which would fail the publish and block the source partition behind it.
     */
    static TopicPartition deadLetterPartition(ConsumerRecord<?, ?> failed, int deadLetterPartitions) {
        int partition = failed.partition() < deadLetterPartitions ? failed.partition() : ANY_PARTITION;
        return new TopicPartition(Topics.deadLetter(failed.topic()), partition);
    }
}
