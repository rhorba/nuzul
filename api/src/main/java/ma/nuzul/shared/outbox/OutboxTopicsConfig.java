package ma.nuzul.shared.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the event topics, so Spring's KafkaAdmin creates them at startup with the partition count we chose rather
 * than the broker's auto-create default. Partitions bound consumer parallelism; one aggregate's events always share
 * one partition (the key is the aggregate id).
 */
@Configuration(proxyBeanMethods = false)
class OutboxTopicsConfig {

    /** Partition count of every event topic and its DLT; also read by {@link KafkaConsumerConfig}. */
    static final String PARTITIONS = "${app.kafka.partitions:3}";

    private final int partitions;
    private final int replicas;

    OutboxTopicsConfig(@Value(PARTITIONS) int partitions, @Value("${app.kafka.replicas:1}") int replicas) {
        this.partitions = partitions;
        this.replicas = replicas;
    }

    @Bean
    NewTopic reservationEventsTopic() {
        return topic(Topics.RESERVATION_EVENTS);
    }

    @Bean
    NewTopic catalogEventsTopic() {
        return topic(Topics.CATALOG_EVENTS);
    }

    /**
     * Same partition count as its topic: a failed record keeps its partition on the DLT (KafkaConsumerConfig). Both
     * come from {@code app.kafka.partitions}, so a topic and its DLT must always be resized together (KafkaAdmin only
     * ever adds partitions); otherwise records from the extra partitions land on any DLT partition.
     */
    @Bean
    NewTopic reservationEventsDeadLetterTopic() {
        return topic(Topics.deadLetter(Topics.RESERVATION_EVENTS));
    }

    @Bean
    NewTopic catalogEventsDeadLetterTopic() {
        return topic(Topics.deadLetter(Topics.CATALOG_EVENTS));
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }
}
