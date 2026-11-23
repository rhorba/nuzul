package ma.nuzul.shared.ratelimit;

/**
 * Port: may {@code subject} (e.g. a guest's {@code sub}) make one more request under {@code rule}? Each
 * (rule, subject) pair has its own bucket. Implementations fail open: if the limiter's store is down the answer is
 * "allowed", because a rate limit protects capacity and must never be the reason the booking path is unavailable.
 */
public interface RateLimiter {

    /**
     * Guest POSTs under {@code /api/v1/reservations} (hold, confirm, cancel) per guest (config
     * {@code app.rate-limit.reservation-post}).
     */
    String RESERVATION_POST = "reservation-post";

    /**
     * Takes one token from the (rule, subject) bucket.
     *
     * @throws IllegalArgumentException if {@code rule} is not configured (a programming error, not a Redis one)
     */
    Decision tryConsume(String rule, String subject);

    /** {@code retryAfterSeconds} is at least 1 when rejected and 0 when allowed. */
    record Decision(boolean allowed, long retryAfterSeconds) {

        public static Decision allow() {
            return new Decision(true, 0);
        }

        public static Decision reject(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }
}
