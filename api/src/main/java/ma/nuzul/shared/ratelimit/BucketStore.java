package ma.nuzul.shared.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;

/**
 * Where buckets live (Redis in production). A seam so the limiter's decision, metric and fail-open logic can be
 * unit-tested without a Redis. Implementations may throw on any store failure; the limiter turns that into "allowed".
 */
@FunctionalInterface
interface BucketStore {

    /** Takes one token from the bucket at {@code key}, creating it from {@code config} if absent. */
    ConsumptionProbe tryConsumeOne(String key, BucketConfiguration config);
}
