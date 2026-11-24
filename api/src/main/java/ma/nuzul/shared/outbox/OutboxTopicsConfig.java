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

    private final int partitions;
    private final int replicas;

    OutboxTopicsConfig(
            @Value("${app.kafka.partitions:3}") int partitions, @Value("${app.kafka.replicas:1}") int replicas) {
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

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }
}
