package ma.nuzul.shared.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.rate-limit.*} (story 2.5). Validated at startup so a typo (capacity 0, missing period) stops the app
 * instead of silently rejecting or allowing every request.
 *
 * @param reservationPost bucket for {@link RateLimiter#RESERVATION_POST}
 * @param maxActiveHolds unpaid HELD reservations a guest may have at once
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        @Valid @NotNull Rule reservationPost, @Positive int maxActiveHolds) {

    /** {@code capacity} requests per {@code period}; the whole capacity comes back at the end of each period. */
    public record Rule(@Positive long capacity, @NotNull Duration period) {}
}
