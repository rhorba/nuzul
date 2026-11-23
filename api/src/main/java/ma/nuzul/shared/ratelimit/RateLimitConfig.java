package ma.nuzul.shared.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.lettuce.core.RedisURI;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Redis rate limiter. The Redis address comes from {@link DataRedisConnectionDetails}, which is built from
 * {@code spring.data.redis.url} in an app and from the container in tests; the timeouts are the same short
 * {@code spring.data.redis.timeout}/{@code connect-timeout} as the idempotency cache. The client runs on Boot's shared
 * Lettuce {@link ClientResources} (event loops, timers), injected directly so Spring destroys this store first.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

    static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(300);

    /** Intervally, not greedy: the whole capacity comes back at the end of each period, which is what guests see. */
    static BucketConfiguration bucketConfiguration(RateLimitProperties.Rule rule) {
        return BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(rule.capacity()).refillIntervally(rule.capacity(), rule.period()))
                .build();
    }

    @Bean(destroyMethod = "close")
    LettuceBucketStore rateLimitBucketStore(
            DataRedisConnectionDetails redis, DataRedisProperties properties, ClientResources lettuceResources) {
        Duration timeout = orDefault(properties.getTimeout());
        return new LettuceBucketStore(
                lettuceResources,
                redisUri(redis, properties.getUrl(), timeout),
                timeout,
                orDefault(properties.getConnectTimeout()),
                System::nanoTime);
    }

    /**
     * TLS when Boot has an SSL bundle ({@code spring.data.redis.ssl.enabled}) or the URL says {@code rediss://}: Boot
     * only builds a bundle in the first case, and plaintext to a TLS Redis would leave the limiter failing open.
     */
    static RedisURI redisUri(DataRedisConnectionDetails redis, @Nullable String url, Duration timeout) {
        var standalone = redis.getStandalone();
        boolean ssl = redis.getSslBundle() != null || (url != null && url.regionMatches(true, 0, "rediss://", 0, 9));
        var uri = RedisURI.builder()
                .withHost(standalone.getHost())
                .withPort(standalone.getPort())
                .withDatabase(standalone.getDatabase())
                .withSsl(ssl)
                .withTimeout(timeout);
        String password = redis.getPassword();
        if (password != null) {
            String username = redis.getUsername();
            if (username != null) {
                uri.withAuthentication(username, password);
            } else {
                uri.withPassword(password.toCharArray());
            }
        }
        return uri.build();
    }

    @Bean
    RedisRateLimiter rateLimiter(RateLimitProperties properties, LettuceBucketStore store, MeterRegistry meters) {
        return new RedisRateLimiter(
                Map.of(RateLimiter.RESERVATION_POST, bucketConfiguration(properties.reservationPost())), store, meters);
    }

    private static Duration orDefault(@Nullable Duration timeout) {
        return timeout == null ? DEFAULT_TIMEOUT : timeout;
    }
}
