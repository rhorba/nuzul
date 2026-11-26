package ma.nuzul.search.adapter.in.kafka;

import java.time.Clock;
import java.time.Duration;
import ma.nuzul.search.application.SearchUnavailableException;
import ma.nuzul.shared.outbox.ListenerErrorHandlers;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The search indexer's own listener container factory: Boot's settings (same configurer as the default factory) and
 * the standard error handler, except that an outage ({@link #outageCause}: Elasticsearch or Postgres unavailable,
 * anywhere in the cause chain) is retried without limit, every 1 s growing to every {@value #OUTAGE_MAX_INTERVAL_MS}
 * ms, instead of 3 times and then to the DLT. Other failures keep the bounded retries and the DLT; bad headers and
 * payloads still go straight to the DLT. Other listeners keep the shared handler. While it waits, {@link OutageRetryLog}
 * logs a WARN per stuck partition once a minute.
 *
 * <p>Trade-off: while Elasticsearch is down, the indexer's partitions stop at the failing record and fall behind.
 * Nothing else waits on them: catalog and rate writes commit with their outbox rows as usual, and the relay keeps
 * publishing. Search is eventually consistent anyway; when the cluster is back the indexer resumes from the stuck
 * record and catches up in order. Dead-lettering instead would silently drop every change made during the outage
 * from search until each hotel changed again or a reindex ran. A 30 s cap keeps each wait well inside
 * {@code max.poll.interval.ms}, so the consumer never leaves its group while it waits.
 */
@Configuration(proxyBeanMethods = false)
class SearchIndexerKafkaConfig {

    static final String CONTAINER_FACTORY = "searchIndexerContainerFactory";

    static final long OUTAGE_INITIAL_INTERVAL_MS = 1_000;
    static final double OUTAGE_MULTIPLIER = 2.0;
    static final long OUTAGE_MAX_INTERVAL_MS = 30_000;

    /** At most one outage WARN per partition per this interval. */
    static final Duration OUTAGE_LOG_INTERVAL = Duration.ofMinutes(1);

    @Bean(CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> searchIndexerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafka,
            @Value(ListenerErrorHandlers.PARTITIONS) int partitions) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errorHandler(kafka, partitions));
        return factory;
    }

    static DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafka, int partitions) {
        // The system clock, not the application's Clock bean: this only paces log lines, and a test's fixed clock
        // would silence every warning after the first.
        return withOutageRetry(ListenerErrorHandlers.standard(kafka, partitions), outageBackOff(), Clock.systemUTC());
    }

    /**
     * {@code handler} with {@code outageBackOff} for outages; its own (bounded) back-off for everything else; outage
     * retries logged on {@code clock}'s time.
     */
    static DefaultErrorHandler withOutageRetry(DefaultErrorHandler handler, BackOff outageBackOff, Clock clock) {
        handler.setBackOffFunction((record, exception) -> isOutage(exception) ? outageBackOff : null);
        // An outage that ends in a different failure (or the reverse) starts that failure's own retry count afresh.
        handler.setResetStateOnExceptionChange(true);
        handler.setRetryListeners(new OutageRetryLog(clock, OUTAGE_LOG_INTERVAL));
        return handler;
    }

    static boolean isOutage(Throwable exception) {
        return outageCause(exception) != null;
    }

    /**
     * The first cause in {@code exception}'s chain that says a dependency is down rather than the record or the code
     * being wrong, or null. Such a failure passes once the dependency is back, so the record waits for it instead of
     * being dead-lettered:
     *
     * <ul>
     *   <li>{@link SearchUnavailableException}: Elasticsearch unreachable, timed out, 429 or 5xx.
     *   <li>{@link TransientDataAccessException}: Spring's "may succeed if retried" (query or lock timeout, deadlock,
     *       a connection reset mid-statement, an exhausted pool).
     *   <li>{@link DataAccessResourceFailureException}: Postgres unreachable, including
     *       {@code CannotGetJdbcConnectionException}, its subclass.
     *   <li>{@link CannotCreateTransactionException}: no connection to begin the claim's transaction with; the
     *       transaction manager wraps the raw {@code SQLException}, so no DataAccessException is in the chain. It is
     *       not a DataAccessException either, hence listed on its own.
     * </ul>
     *
     * Every other DataAccessException (constraint violation, bad SQL, ...) is a bug or bad data: retrying it forever
     * would block the partition for good, so it keeps the bounded retries and the DLT.
     */
    static @Nullable Throwable outageCause(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SearchUnavailableException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException) {
                return cause;
            }
            if (cause.getCause() == cause) {
                return null;
            }
        }
        return null;
    }

    /** No max attempts and no max elapsed time: the record waits for the cluster. */
    static ExponentialBackOff outageBackOff() {
        var backOff = new ExponentialBackOff(OUTAGE_INITIAL_INTERVAL_MS, OUTAGE_MULTIPLIER);
        backOff.setMaxInterval(OUTAGE_MAX_INTERVAL_MS);
        return backOff;
    }
}
