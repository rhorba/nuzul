package ma.nuzul.search.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import ma.nuzul.search.application.SearchUnavailableException;
import ma.nuzul.shared.outbox.InvalidEventHeaderException;
import ma.nuzul.shared.outbox.InvalidEventPayloadException;
import ma.nuzul.shared.outbox.ListenerErrorHandlers;
import ma.nuzul.shared.outbox.Topics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Story 3.2: the search indexer waits out an Elasticsearch outage or a transient Postgres failure (and says so in the
 * log, rate-limited), and dead-letters everything else as before.
 */
class SearchIndexerKafkaConfigTest {

    /** Enough deliveries to tell "never recovered" from the standard 1 + 3. */
    private static final int MAX_DELIVERIES = 50;

    private final List<Exception> recovered = new ArrayList<>();
    private final MutableClock clock = new MutableClock();
    /** The real classification, with no waits: 3 bounded retries, and an unbounded retry for outages. */
    private final DefaultErrorHandler handler = SearchIndexerKafkaConfig.withOutageRetry(
            ListenerErrorHandlers.standard((failed, e) -> recovered.add(e), new FixedBackOff(0, 3)),
            new FixedBackOff(0, FixedBackOff.UNLIMITED_ATTEMPTS),
            clock);

    private final ConsumerRecord<String, String> failedRecord =
            new ConsumerRecord<>(Topics.CATALOG_EVENTS, 1, 7L, "key", "secret-payload");
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    private final Logger outageLogger = (Logger) LoggerFactory.getLogger(OutageRetryLog.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    {
        when(container.isRunning()).thenReturn(true);
    }

    @BeforeEach
    void captureLogs() {
        logs.start();
        outageLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        outageLogger.detachAppender(logs);
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
            done = deliver(cause);
        } while (!done && deliveries < MAX_DELIVERIES);
        return deliveries;
    }

    private boolean deliver(Throwable cause) {
        return handler.handleOne(
                new ListenerExecutionFailedException("listener failed", cause), failedRecord, consumer, container);
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void anOutageIsRetriedWithoutLimitAndNeverDeadLettered() {
        assertThat(deliveriesUntilRecovered(outage())).isEqualTo(MAX_DELIVERIES);
        assertThat(recovered).isEmpty();
    }

    @Test
    void aTransientPostgresFailureIsRetriedWithoutLimitLikeAnOutage() {
        List<Throwable> transients = List.of(
                new QueryTimeoutException("statement timeout"),
                new TransientDataAccessResourceException("connection reset"),
                new DataAccessResourceFailureException("database down"),
                new CannotGetJdbcConnectionException("pool exhausted", new SQLTransientConnectionException()),
                // What the transaction manager throws when it cannot even open a connection: not a DataAccessException.
                new CannotCreateTransactionException(
                        "Could not open JDBC Connection for transaction", new SQLTransientConnectionException()),
                new IllegalStateException("wrapped", new QueryTimeoutException("statement timeout")));

        for (Throwable failure : transients) {
            assertThat(deliveriesUntilRecovered(failure)).as(failure.toString()).isEqualTo(MAX_DELIVERIES);
            deliveriesUntilRecovered(new IllegalStateException("reset the record's retry state"));
            recovered.clear();
        }
    }

    @Test
    void aNonTransientDataAccessFailureKeepsTheBoundedRetriesThenTheDeadLetterTopic() {
        assertThat(deliveriesUntilRecovered(new DuplicateKeyException("duplicate")))
                .isEqualTo(4);
        assertThat(deliveriesUntilRecovered(new DataIntegrityViolationException("not null")))
                .isEqualTo(4);
        assertThat(deliveriesUntilRecovered(new BadSqlGrammarException("sync", "SELECT", new java.sql.SQLException())))
                .isEqualTo(4);
        assertThat(recovered).hasSize(3);
    }

    @Test
    void anOutageThatTurnsIntoAnotherFailureGetsThatFailuresBoundedRetriesThenTheDeadLetterTopic() {
        for (int i = 0; i < 10; i++) {
            assertThat(deliver(outage())).isFalse();
        }

        assertThat(deliveriesUntilRecovered(new IllegalStateException("bulk rejected")))
                .isEqualTo(4);
        assertThat(recovered)
                .singleElement()
                .satisfies(e -> assertThat(e).hasRootCauseInstanceOf(IllegalStateException.class));
    }

    @Test
    void anyOtherHandlerFailureKeepsTheBoundedRetriesThenTheDeadLetterTopic() {
        assertThat(deliveriesUntilRecovered(new IllegalStateException("bulk rejected")))
                .isEqualTo(4);
        // A transport error the adapter did not classify as an outage (e.g. a 400 without error body) stays bounded.
        assertThat(deliveriesUntilRecovered(new UncheckedIOException(new java.io.IOException("HTTP 400"))))
                .isEqualTo(4);
        assertThat(recovered).hasSize(2);
        assertThat(warnings()).isEmpty();
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
        assertThat(SearchIndexerKafkaConfig.isOutage(outage())).isTrue();
        assertThat(SearchIndexerKafkaConfig.isOutage(new IllegalStateException("wrapped", outage())))
                .isTrue();
        assertThat(SearchIndexerKafkaConfig.isOutage(new UncheckedIOException(new ConnectException())))
                .isFalse();
        assertThat(SearchIndexerKafkaConfig.outageCause(new IllegalStateException("wrapped", outage())))
                .isInstanceOf(SearchUnavailableException.class);
    }

    @Test
    void anOutageIsLoggedOncePerPartitionPerMinuteWithTheRecordPositionAndCauseButNotThePayload() {
        for (int i = 0; i < 20; i++) {
            deliver(outage());
            clock.advance(Duration.ofSeconds(2)); // 40 s in all: still inside the first minute
        }

        assertThat(warnings())
                .singleElement()
                .satisfies(message -> assertThat(message)
                        .contains(Topics.CATALOG_EVENTS + "-1@7")
                        .contains("SearchUnavailableException")
                        .contains("Elasticsearch unreachable")
                        .doesNotContain("secret-payload"));

        clock.advance(Duration.ofSeconds(20)); // a minute since the first warning
        deliver(new QueryTimeoutException("statement timeout"));
        deliver(new QueryTimeoutException("statement timeout"));

        assertThat(warnings()).hasSize(2);
        assertThat(warnings().getLast()).contains("QueryTimeoutException").contains("statement timeout");
    }

    @Test
    void eachPartitionGetsItsOwnOutageWarning() {
        var log = new OutageRetryLog(clock, Duration.ofMinutes(1));
        var otherPartition = new ConsumerRecord<>(Topics.CATALOG_EVENTS, 2, 3L, "key", "value");

        log.failedDelivery(failedRecord, outage(), 1);
        log.failedDelivery(otherPartition, outage(), 1);
        log.failedDelivery(failedRecord, outage(), 2);
        log.failedDelivery(failedRecord, new IllegalStateException("not an outage"), 3);

        assertThat(warnings()).hasSize(2);
        assertThat(warnings().getLast()).contains(Topics.CATALOG_EVENTS + "-2@3");
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

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-11-26T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
