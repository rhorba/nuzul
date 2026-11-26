package ma.nuzul.search.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import ma.nuzul.search.application.SearchUnavailableException;
import ma.nuzul.shared.outbox.InvalidEventHeaderException;
import ma.nuzul.shared.outbox.InvalidEventPayloadException;
import ma.nuzul.shared.outbox.ListenerErrorHandlers;
import ma.nuzul.shared.outbox.Topics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

/** Story 3.2: the search indexer waits out an Elasticsearch outage, and dead-letters everything else as before. */
class SearchIndexerKafkaConfigTest {

    /** Enough deliveries to tell "never recovered" from the standard 1 + 3. */
    private static final int MAX_DELIVERIES = 50;

    private final List<Exception> recovered = new ArrayList<>();
    /** The real classification, with no waits: 3 bounded retries, and an unbounded retry for outages. */
    private final DefaultErrorHandler handler = SearchIndexerKafkaConfig.withOutageRetry(
            ListenerErrorHandlers.standard((failed, e) -> recovered.add(e), new FixedBackOff(0, 3)),
            new FixedBackOff(0, FixedBackOff.UNLIMITED_ATTEMPTS));

    private final ConsumerRecord<String, String> failedRecord =
            new ConsumerRecord<>(Topics.CATALOG_EVENTS, 1, 7L, "key", "value");
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    {
        when(container.isRunning()).thenReturn(true);
    }

    private static SearchUnavailableException outage() {
        return new SearchUnavailableException("Elasticsearch unreachable", new ConnectException("refused"));
    }

    /** Delivers the failure, wrapped as the container does, until recovered; returns how many deliveries it took. */
    private int deliveriesUntilRecovered(Throwable cause) {
        int deliveries = 0;
        boolean done;
        do {
            deliveries++;
            done = handler.handleOne(
                    new ListenerExecutionFailedException("listener failed", cause), failedRecord, consumer, container);
        } while (!done && deliveries < MAX_DELIVERIES);
        return deliveries;
    }

    @Test
    void anOutageIsRetriedWithoutLimitAndNeverDeadLettered() {
        assertThat(deliveriesUntilRecovered(outage())).isEqualTo(MAX_DELIVERIES);
        assertThat(recovered).isEmpty();
    }

    @Test
    void anyOtherHandlerFailureKeepsTheBoundedRetriesThenTheDeadLetterTopic() {
        assertThat(deliveriesUntilRecovered(new IllegalStateException("bulk rejected")))
                .isEqualTo(4);
        // A transport error the adapter did not classify as an outage (e.g. a 400 without error body) stays bounded.
        assertThat(deliveriesUntilRecovered(new UncheckedIOException(new java.io.IOException("HTTP 400"))))
                .isEqualTo(4);
        assertThat(recovered).hasSize(2);
    }

    @Test
    void badHeadersAndPayloadsStillGoStraightToTheDeadLetterTopic() {
        assertThat(deliveriesUntilRecovered(new InvalidEventPayloadException("catalog event lacks hotelId")))
                .isEqualTo(1);
        assertThat(deliveriesUntilRecovered(new InvalidEventHeaderException("event-id header is missing")))
                .isEqualTo(1);
        assertThat(recovered).hasSize(2);
    }

    @Test
    void anOutageIsRecognisedAnywhereInTheCauseChainOnly() {
        assertThat(SearchIndexerKafkaConfig.isSearchOutage(outage())).isTrue();
        assertThat(SearchIndexerKafkaConfig.isSearchOutage(new IllegalStateException("wrapped", outage())))
                .isTrue();
        assertThat(SearchIndexerKafkaConfig.isSearchOutage(new UncheckedIOException(new ConnectException())))
                .isFalse();
    }

    @Test
    void theOutageBackOffNeverStopsAndIsCappedAtThirtySeconds() {
        BackOffExecution execution = SearchIndexerKafkaConfig.outageBackOff().start();
        List<Long> intervals = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            intervals.add(execution.nextBackOff());
        }

        assertThat(intervals)
                .doesNotContain(BackOffExecution.STOP)
                .allSatisfy(interval -> assertThat(interval).isBetween(1_000L, 30_000L));
        assertThat(intervals.getFirst()).isEqualTo(1_000L);
        assertThat(intervals.getLast()).isEqualTo(30_000L);
    }
}
