package ma.nuzul.shared.error;

import java.util.Map;
import java.util.OptionalLong;

/**
 * The caller is sending too much (429): a rate limit, or a cap such as too many unpaid holds. A rate limit knows when
 * its bucket refills and passes {@code retryAfterSeconds} (sent as a {@code Retry-After} header); a cap does not,
 * because it lifts only when the caller acts (pays or cancels), so it omits it.
 */
public class TooManyRequestsException extends CodedException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String code, String message) {
        super(code, message, Map.of());
        this.retryAfterSeconds = -1;
    }

    public TooManyRequestsException(String code, String message, long retryAfterSeconds) {
        super(code, message, Map.of());
        if (retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds must not be negative: " + retryAfterSeconds);
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public OptionalLong retryAfterSeconds() {
        return retryAfterSeconds < 0 ? OptionalLong.empty() : OptionalLong.of(retryAfterSeconds);
    }
}
