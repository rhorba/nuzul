package ma.nuzul.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

/** Story 3.1: every event topic and its dead-letter topic is declared with the same partition count. */
class OutboxTopicsConfigTest {

    private final OutboxTopicsConfig config = new OutboxTopicsConfig(4, 2);

    @Test
    void declaresBothEventTopicsAndTheirDeadLetterTopics() {
        List<NewTopic> topics = List.of(
                config.reservationEventsTopic(),
                config.catalogEventsTopic(),
                config.reservationEventsDeadLetterTopic(),
                config.catalogEventsDeadLetterTopic());

        assertThat(topics)
                .extracting(NewTopic::name)
                .containsExactly(
                        "reservation-events", "catalog-events", "reservation-events.DLT", "catalog-events.DLT");
        assertThat(topics).allSatisfy(topic -> {
            assertThat(topic.numPartitions()).isEqualTo(4);
            assertThat(topic.replicationFactor()).isEqualTo((short) 2);
        });
    }
}
