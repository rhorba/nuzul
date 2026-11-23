package ma.nuzul.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The wired limiter against a real Redis (Testcontainers): capacity N then a rejection with a retry-after, buckets
 * per subject and per rule, an expiring key, and a refill once the (test-sized, 2 s) period has passed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        properties = {"app.rate-limit.reservation-post.capacity=3", "app.rate-limit.reservation-post.period=PT2S"})
class RedisRateLimiterIT {

    static final String RULE = RateLimiter.RESERVATION_POST;
    static final int CAPACITY = 3;

    @Autowired
    RateLimiter limiter;

    @Autowired
    RateLimitProperties properties;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    static String subject() {
        return "guest-" + UUID.randomUUID();
    }

    void consumeCapacity(String subject) {
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(limiter.tryConsume(RULE, subject).allowed())
                    .as("request %d of %d", i + 1, CAPACITY)
                    .isTrue();
        }
    }

    double rejectedCount() {
        var counter =
                meters.find(RedisRateLimiter.REJECTED_METRIC).tag("rule", RULE).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void testPropertiesAreBound() {
        assertThat(properties.reservationPost().capacity()).isEqualTo(CAPACITY);
        assertThat(properties.reservationPost().period()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.maxActiveHolds()).isEqualTo(5);
    }

    @Test
    void capacityIsAllowedThenTheNextIsRejectedWithRetryAfterAndCounted() {
        String guest = subject();
        double before = rejectedCount();

        consumeCapacity(guest);
        var rejected = limiter.tryConsume(RULE, guest);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 2L);
        assertThat(rejectedCount()).isEqualTo(before + 1);
    }

    @Test
    void subjectsHaveIndependentBuckets() {
        String exhausted = subject();
        consumeCapacity(exhausted);
        assertThat(limiter.tryConsume(RULE, exhausted).allowed()).isFalse();

        assertThat(limiter.tryConsume(RULE, subject()).allowed()).isTrue();
    }

    @Test
    void bucketKeyIsPerRuleAndSubjectAndExpires() {
        String guest = subject();

        limiter.tryConsume(RULE, guest);

        String key = "rl:" + RULE + ":" + guest;
        assertThat(redis.hasKey(key)).isTrue();
        Long ttlMillis = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttlMillis)
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofSeconds(10).toMillis());
    }

    @Test
    @Timeout(15)
    void bucketRefillsAfterThePeriod() throws InterruptedException {
        String guest = subject();
        consumeCapacity(guest);
        var rejected = limiter.tryConsume(RULE, guest);
        assertThat(rejected.allowed()).isFalse();

        Thread.sleep(Duration.ofSeconds(rejected.retryAfterSeconds()).plusMillis(200));

        consumeCapacity(guest);
    }
}
