package ma.nuzul.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.MismatchedInputException;

/** Story 3.1: which failures are retried and which go straight to the DLT (the real broker is IdempotentConsumerIT). */
class KafkaConsumerConfigTest {

    private final List<Exception> recovered = new ArrayList<>();
    /** No wait between retries, same retry count as production. */
    private final DefaultErrorHandler handler = KafkaConsumerConfig.errorHandler(
            (failed, e) -> recovered.add(e), new FixedBackOff(0, KafkaConsumerConfig.MAX_RETRIES));

    private final ConsumerRecord<String, String> failedRecord =
            new ConsumerRecord<>(Topics.RESERVATION_EVENTS, 2, 41L, "key", "value");
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    {
        when(container.isRunning()).thenReturn(true);
    }

    /** As the container hands it to the error handler: wrapped in ListenerExecutionFailedException. */
    private static Exception thrownByListener(Throwable cause) {
        return new ListenerExecutionFailedException("listener failed", cause);
    }

    /** Delivers the failure until the handler recovers it; returns how many deliveries that took. */
    private int deliveriesUntilRecovered(Throwable cause) {
        int deliveries = 0;
        boolean done;
        do {
            deliveries++;
            done = handler.handleOne(thrownByListener(cause), failedRecord, consumer, container);
        } while (!done && deliveries < 10);
        return deliveries;
    }

    @Test
    void anInvalidEventHeaderGoesStraightToTheDeadLetterTopic() {
        assertThat(deliveriesUntilRecovered(new InvalidEventHeaderException("event-id header is missing")))
                .isEqualTo(1);
        assertThat(recovered).hasSize(1);
    }

    @Test
    void anUnreadablePayloadGoesStraightToTheDeadLetterTopic() {
        assertThat(deliveriesUntilRecovered(new InvalidEventPayloadException(
                        "not a ReservationEvent", new StreamReadException(null, "Unexpected character"))))
                .isEqualTo(1);
        // A tombstone (null value) is rejected by the listener the same way.
        assertThat(deliveriesUntilRecovered(new InvalidEventPayloadException("reservation event has no value")))
                .isEqualTo(1);
    }

    @Test
    void anyOtherFailureIsRetriedThreeTimesFirst() {
        assertThat(deliveriesUntilRecovered(new IllegalStateException("database down")))
                .isEqualTo(4);
        assertThat(deliveriesUntilRecovered(new IllegalArgumentException("not obviously poison")))
                .isEqualTo(4);
        assertThat(recovered).hasSize(2);
    }

    @Test
    void aJacksonFailureFromAHandlerIsRetried() {
        assertThat(deliveriesUntilRecovered(new StreamReadException(null, "provider sent HTML")))
                .isEqualTo(4);
        assertThat(deliveriesUntilRecovered(MismatchedInputException.from(null, String.class, "wrong shape")))
                .isEqualTo(4);
    }

    @Test
    void theBackOffDoublesFromHalfASecondAndStopsAfterThreeRetries() {
        BackOffExecution execution = KafkaConsumerConfig.backOff().start();

        assertThat(execution.nextBackOff()).isEqualTo(500);
        assertThat(execution.nextBackOff()).isEqualTo(1_000);
        assertThat(execution.nextBackOff()).isEqualTo(2_000);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void aDeadLetterKeepsItsTopicsPartition() {
        assertThat(KafkaConsumerConfig.deadLetterPartition(failedRecord, 3)).satisfies(tp -> {
            assertThat(tp.topic()).isEqualTo("reservation-events.DLT");
            assertThat(tp.partition()).isEqualTo(2);
        });
    }

    @Test
    void aPartitionTheDeadLetterTopicIsNotDeclaredWithGoesToAnyPartition() {
        assertThat(KafkaConsumerConfig.deadLetterPartition(failedRecord, 2).partition())
                .isEqualTo(KafkaConsumerConfig.ANY_PARTITION);
    }
}
