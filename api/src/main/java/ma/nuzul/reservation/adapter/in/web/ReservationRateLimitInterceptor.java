package ma.nuzul.reservation.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ma.nuzul.shared.error.TooManyRequestsException;
import ma.nuzul.shared.ratelimit.RateLimiter;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Story 2.5: every reservation POST (hold, confirm, cancel) takes one token from the guest's
 * {@link RateLimiter#RESERVATION_POST} bucket. An MVC interceptor, so it runs:
 *
 * <ul>
 *   <li>after Spring Security: a request without a token (401) or without the GUEST role (403) is refused by the
 *       filter chain and never takes a token, so nobody can drain a guest's bucket;
 *   <li>before the {@code @RequestBody} is read and validated: an invalid body still costs a token, so a client
 *       cannot hammer the endpoint with bodies it knows will fail.
 * </ul>
 *
 * The bucket is keyed by the token's {@code sub} only, never by a header or the body, which the caller controls.
 * Over the limit it throws, and the problem handler answers 429 RATE_LIMITED with {@code Retry-After}.
 */
class ReservationRateLimitInterceptor implements HandlerInterceptor {

    static final String RATE_LIMITED = "RATE_LIMITED";

    private final RateLimiter limiter;

    ReservationRateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // The filter chain only lets authenticated GUEST tokens through to these paths; without a JWT there is no
        // subject to key on, and refusing is the filter chain's job, not the limiter's.
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return true;
        }
        var decision = limiter.tryConsume(RateLimiter.RESERVATION_POST, jwt.getSubject());
        if (!decision.allowed()) {
            throw new TooManyRequestsException(
                    RATE_LIMITED,
                    "Too many reservation requests. Try again in " + decision.retryAfterSeconds() + " seconds.",
                    decision.retryAfterSeconds());
        }
        return true;
    }
}
