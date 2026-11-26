package ma.nuzul.search.adapter.in.kafka;

import ma.nuzul.search.application.SearchUnavailableException;
import ma.nuzul.shared.outbox.ListenerErrorHandlers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The search indexer's own listener container factory: Boot's settings (same configurer as the default factory) and
 * the standard error handler, except that an Elasticsearch outage ({@link SearchUnavailableException} anywhere in the
 * cause chain) is retried without limit, every 1 s growing to every {@value #OUTAGE_MAX_INTERVAL_MS} ms, instead of
 * 3 times and then to the DLT. Other failures keep the bounded retries and the DLT; bad headers and payloads still go
 * straight to the DLT. Other listeners keep the shared handler.
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
        return withOutageRetry(ListenerErrorHandlers.standard(kafka, partitions), outageBackOff());
    }

    /** {@code handler} with {@code outageBackOff} for outages; its own (bounded) back-off for everything else. */
    static DefaultErrorHandler withOutageRetry(DefaultErrorHandler handler, BackOff outageBackOff) {
        handler.setBackOffFunction((record, exception) -> isSearchOutage(exception) ? outageBackOff : null);
        // An outage that ends in a different failure (or the reverse) starts that failure's own retry count afresh.
        handler.setResetStateOnExceptionChange(true);
        return handler;
    }

    static boolean isSearchOutage(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SearchUnavailableException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    /** No max attempts and no max elapsed time: the record waits for the cluster. */
    static ExponentialBackOff outageBackOff() {
        var backOff = new ExponentialBackOff(OUTAGE_INITIAL_INTERVAL_MS, OUTAGE_MULTIPLIER);
        backOff.setMaxInterval(OUTAGE_MAX_INTERVAL_MS);
        return backOff;
    }
}
