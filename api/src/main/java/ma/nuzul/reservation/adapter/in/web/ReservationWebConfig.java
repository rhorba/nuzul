package ma.nuzul.reservation.adapter.in.web;

import ma.nuzul.shared.ratelimit.RateLimiter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the reservation rate limit on the reservation API. The bare collection path is listed on its own (the
 * hold is {@code POST /api/v1/reservations}) rather than relying on {@code /**} matching zero segments.
 */
@Configuration(proxyBeanMethods = false)
class ReservationWebConfig implements WebMvcConfigurer {

    private final RateLimiter limiter;

    ReservationWebConfig(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ReservationRateLimitInterceptor(limiter))
                .addPathPatterns("/api/v1/reservations", "/api/v1/reservations/**");
    }
}
