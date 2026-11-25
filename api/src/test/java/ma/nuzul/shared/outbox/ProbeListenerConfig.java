package ma.nuzul.shared.outbox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;

/**
 * A test-only listener on its own topic (and DLT), going through the production error handler and
 * {@link IdempotentConsumer} like a real consumer, but whose handler can be told to fail and counts what it did.
 * Tests use a fresh record key each, so their counters never mix.
 */
@TestConfiguration(proxyBeanMethods = false)
class ProbeListenerConfig {

    static final String TOPIC = "idempotent-consumer-it";
    static final String CONSUMER = "probe";
    static final int PARTITIONS = 3;

    @Bean
    NewTopic probeTopic() {
        return TopicBuilder.name(TOPIC).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic probeDeadLetterTopic() {
        return TopicBuilder.name(Topics.deadLetter(TOPIC))
                .partitions(PARTITIONS)
                .replicas(1)
                .build();
    }

    @Bean
    ProbeListener probeListener(IdempotentConsumer idempotentConsumer) {
        return new ProbeListener(idempotentConsumer);
    }

    static class ProbeListener {

        private final IdempotentConsumer idempotentConsumer;
        /** Every delivery the listener saw, per record key, before any header is read. */
        private final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        /** Handler runs that committed (or were about to), per event id. */
        private final Map<UUID, AtomicInteger> handled = new ConcurrentHashMap<>();
        /** How many more times the handler fails for a key; absent means never. */
        private final Map<String, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();

        ProbeListener(IdempotentConsumer idempotentConsumer) {
            this.idempotentConsumer = idempotentConsumer;
        }

        @KafkaListener(topics = TOPIC, groupId = CONSUMER)
        void onEvent(ConsumerRecord<String, String> event) {
            deliveries.computeIfAbsent(event.key(), k -> new AtomicInteger()).incrementAndGet();
            UUID eventId = EventHeaders.eventId(event.headers());
            idempotentConsumer.handleOnce(CONSUMER, eventId, () -> {
                AtomicInteger left = failuresLeft.get(event.key());
                if (left != null && left.getAndDecrement() > 0) {
                    throw new IllegalStateException("probe failure for " + event.key());
                }
                handled.computeIfAbsent(eventId, k -> new AtomicInteger()).incrementAndGet();
            });
        }

        void failTimes(String key, int times) {
            failuresLeft.put(key, new AtomicInteger(times));
        }

        int deliveries(String key) {
            AtomicInteger count = deliveries.get(key);
            return count == null ? 0 : count.get();
        }

        int handled(UUID eventId) {
            AtomicInteger count = handled.get(eventId);
            return count == null ? 0 : count.get();
        }
    }
}
