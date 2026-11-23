package ma.nuzul.shared.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token buckets in Redis, one per {@code rl:{rule}:{subject}} key. Fail-open: any store failure (Redis down, slow,
 * unreadable) allows the request, the same stance as the idempotency cache. Failures are logged at WARN and recoveries at INFO,
 * each at most once per {@link #WARN_INTERVAL} (monotonic time), never once per request. Rejections are
 * counted in {@value #REJECTED_METRIC}, tagged by rule, so an abusive client or a too-tight limit shows on a dashboard.
 */
class RedisRateLimiter implements RateLimiter {

    static final String REJECTED_METRIC = "nuzul.ratelimit.rejected";

    static final Duration WARN_INTERVAL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private final Map<String, BucketConfiguration> rules;
    private final BucketStore store;
    private final Map<String, Counter> rejected;
    private final LongSupplier nanoTime;
    private final AtomicBoolean degraded = new AtomicBoolean();
    private final AtomicLong lastWarnNanos;
    private final AtomicLong lastInfoNanos;

    RedisRateLimiter(Map<String, BucketConfiguration> rules, BucketStore store, MeterRegistry meters) {
        this(rules, store, meters, System::nanoTime);
    }

    RedisRateLimiter(
            Map<String, BucketConfiguration> rules, BucketStore store, MeterRegistry meters, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        long longAgo = nanoTime.getAsLong() - WARN_INTERVAL.toNanos();
        this.lastWarnNanos = new AtomicLong(longAgo);
        this.lastInfoNanos = new AtomicLong(longAgo);
        this.rules = Map.copyOf(rules);
        this.store = store;
        this.rejected = rules.keySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        rule -> rule,
                        rule -> Counter.builder(REJECTED_METRIC)
                                .description("Requests rejected by a rate limit")
                                .tag("rule", rule)
                                .register(meters)));
    }

    static String key(String rule, String subject) {
        return "rl:" + rule + ":" + subject;
    }

    @Override
    public Decision tryConsume(String rule, String subject) {
        BucketConfiguration config = rules.get(rule);
        if (config == null) {
            throw new IllegalArgumentException("Unknown rate-limit rule: " + rule);
        }
        var probe = consume(rule, subject, config);
        if (probe == null || probe.isConsumed()) {
            return Decision.allow();
        }
        rejected.get(rule).increment();
        return Decision.reject(Math.max(1, ceilSeconds(probe.getNanosToWaitForRefill())));
    }

    /** Null when the store failed: the caller allows the request. */
    private @Nullable ConsumptionProbe consume(String rule, String subject, BucketConfiguration config) {
        try {
            var probe = store.tryConsumeOne(key(rule, subject), config);
            if (degraded.compareAndSet(true, false) && due(lastInfoNanos)) {
                log.info("Rate limiter available again");
            }
            return probe;
        } catch (RuntimeException e) {
            warnThrottled(rule, e);
            return null;
        }
    }

    /**
     * At most one WARN per interval, whichever thread gets there first. Not reset by a recovery: a Redis that is up
     * but slow (some calls time out, some succeed) would otherwise log a WARN/INFO pair on every flip.
     */
    private void warnThrottled(String rule, RuntimeException e) {
        degraded.set(true);
        if (due(lastWarnNanos)) {
            log.warn(
                    "Rate limiter unavailable, allowing requests (rule {}); repeated at most every {}: {}",
                    rule,
                    WARN_INTERVAL,
                    e.toString());
        }
    }

    /** True for one caller once {@link #WARN_INTERVAL} has passed since {@code last}, which it then moves to now. */
    private boolean due(AtomicLong last) {
        long now = nanoTime.getAsLong();
        long previous = last.get();
        return now - previous >= WARN_INTERVAL.toNanos() && last.compareAndSet(previous, now);
    }

    private static long ceilSeconds(long nanos) {
        long perSecond = TimeUnit.SECONDS.toNanos(1);
        return (nanos + perSecond - 1) / perSecond;
    }
}
