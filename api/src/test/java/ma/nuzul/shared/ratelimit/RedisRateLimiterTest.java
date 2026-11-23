package ma.nuzul.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Decision mapping, Redis key, metric and fail-open behaviour of the limiter, with the bucket store faked. Real
 * Redis is covered by {@link RedisRateLimiterIT}; real Lettuce timeouts by {@link LettuceBucketStoreTest}.
 */
class RedisRateLimiterTest {

    static final String RULE = RateLimiter.RESERVATION_POST;

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final List<String> keys = new ArrayList<>();
    final BucketConfiguration config =
            RateLimitConfig.bucketConfiguration(new RateLimitProperties.Rule(3, Duration.ofMinutes(1)));

    final MutableNanoTime clock = new MutableNanoTime();
    final Logger logger = (Logger) LoggerFactory.getLogger(RedisRateLimiter.class);
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    List<Level> levels() {
        return logs.list.stream().map(ILoggingEvent::getLevel).toList();
    }

    RedisRateLimiter limiter(BucketStore store) {
        return new RedisRateLimiter(Map.of(RULE, config), store, meters, clock);
    }

    BucketStore answering(ConsumptionProbe probe) {
        return (key, cfg) -> {
            keys.add(key);
            assertThat(cfg).isSameAs(config);
            return probe;
        };
    }

    double rejected(String rule) {
        var counter =
                meters.find(RedisRateLimiter.REJECTED_METRIC).tag("rule", rule).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void consumedTokenIsAllowedAndUsesThePerRuleSubjectKey() {
        var decision = limiter(answering(ConsumptionProbe.consumed(2, 0))).tryConsume(RULE, "guest-1");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.retryAfterSeconds()).isZero();
        assertThat(keys).containsExactly("rl:reservation-post:guest-1");
        assertThat(rejected(RULE)).isZero();
    }

    @Test
    void rejectionCarriesRetryAfterRoundedUpAndCountsTheRule() {
        long nanos = Duration.ofMillis(1_200).toNanos();
        var limiter = limiter(answering(ConsumptionProbe.rejected(0, nanos, nanos)));

        var decision = limiter.tryConsume(RULE, "guest-1");
        limiter.tryConsume(RULE, "guest-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(2);
        assertThat(rejected(RULE)).isEqualTo(2);
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        var decision = limiter(answering(ConsumptionProbe.rejected(0, 0, 0))).tryConsume(RULE, "guest-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void storeFailureFailsOpenWithoutCountingARejection() {
        BucketStore broken = (key, cfg) -> {
            throw new IllegalStateException("redis down");
        };

        var decision = limiter(broken).tryConsume(RULE, "guest-1");

        assertThat(decision.allowed()).isTrue();
        assertThat(rejected(RULE)).isZero();
    }

    @Test
    void unknownRuleIsAProgrammingError() {
        var limiter = limiter(answering(ConsumptionProbe.consumed(2, 0)));

        assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryConsume("no-such-rule", "guest-1"));
    }

    @Test
    void decisionFactories() {
        assertThat(RateLimiter.Decision.allow()).isEqualTo(new RateLimiter.Decision(true, 0));
        assertThat(RateLimiter.Decision.reject(7)).isEqualTo(new RateLimiter.Decision(false, 7));
    }

    @Test
    void outageWarnsOnceThenAtMostEveryThirtySeconds() {
        BucketStore broken = (key, cfg) -> {
            throw new IllegalStateException("redis down");
        };
        var limiter = limiter(broken);

        limiter.tryConsume(RULE, "guest-1");
        clock.advance(Duration.ofSeconds(10));
        limiter.tryConsume(RULE, "guest-2");
        clock.advance(Duration.ofSeconds(19));
        limiter.tryConsume(RULE, "guest-3");
        assertThat(levels()).containsExactly(Level.WARN);

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryConsume(RULE, "guest-4").allowed()).isTrue();
        assertThat(levels()).containsExactly(Level.WARN, Level.WARN);
    }

    @Test
    void flappingRedisLogsAtMostOneWarnAndOneInfoPerInterval() {
        var down = new java.util.concurrent.atomic.AtomicBoolean();
        BucketStore flaky = (key, cfg) -> {
            if (down.get()) {
                throw new IllegalStateException("redis timed out");
            }
            return ConsumptionProbe.consumed(2, 0);
        };
        var limiter = limiter(flaky);

        for (int i = 0; i < 100; i++) {
            down.set(i % 2 == 0);
            assertThat(limiter.tryConsume(RULE, "guest-1").allowed()).isTrue();
            clock.advance(Duration.ofMillis(250));
        }

        assertThat(levels()).containsExactly(Level.WARN, Level.INFO);
    }

    @Test
    void healthyCallsLogNothing() {
        var limiter = limiter(answering(ConsumptionProbe.consumed(2, 0)));

        limiter.tryConsume(RULE, "guest-1");
        limiter.tryConsume(RULE, "guest-1");

        assertThat(logs.list).isEmpty();
    }
}
