package ma.nuzul.reservation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import ma.nuzul.shared.error.TooManyRequestsException;
import ma.nuzul.shared.ratelimit.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Which requests take a token, from whose bucket, and what a rejection looks like; the limiter is faked. */
class ReservationRateLimitInterceptorTest {

    private final List<String> consumed = new ArrayList<>();
    private RateLimiter.Decision answer = RateLimiter.Decision.allow();
    private final RateLimiter limiter = (rule, subject) -> {
        consumed.add(rule + " " + subject);
        return answer;
    };
    private final ReservationRateLimitInterceptor interceptor = new ReservationRateLimitInterceptor(limiter);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String sub) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(sub).build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));
    }

    private static MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, "/api/v1/reservations");
        request.addHeader("X-Guest-Sub", "spoofed"); // never read: the subject comes from the token only
        return request;
    }

    private boolean preHandle(MockHttpServletRequest request) throws Exception {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void anAllowedPostPassesThroughAndTakesATokenFromTheGuestsBucket() throws Exception {
        authenticateAs("guest-a");

        assertThat(preHandle(request("POST"))).isTrue();
        assertThat(consumed).containsExactly(RateLimiter.RESERVATION_POST + " guest-a");
    }

    @Test
    void aRejectedPostThrowsRateLimitedWithRetryAfter() {
        authenticateAs("guest-a");
        answer = RateLimiter.Decision.reject(42);

        Throwable thrown = catchThrowable(() -> preHandle(request("POST")));

        assertThat(thrown).isInstanceOf(TooManyRequestsException.class);
        var e = (TooManyRequestsException) thrown;
        assertThat(e.code()).isEqualTo(ReservationRateLimitInterceptor.RATE_LIMITED);
        assertThat(e.retryAfterSeconds()).hasValue(42);
    }

    @Test
    void otherMethodsAreNotLimited() throws Exception {
        authenticateAs("guest-a");
        answer = RateLimiter.Decision.reject(42);

        for (String method : List.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE")) {
            assertThat(preHandle(request(method))).as(method).isTrue();
        }
        assertThat(consumed).isEmpty();
    }

    @Test
    void separateGuestsUseSeparateBuckets() throws Exception {
        authenticateAs("guest-a");
        preHandle(request("POST"));
        authenticateAs("guest-b");
        preHandle(request("POST"));

        assertThat(consumed)
                .containsExactly(RateLimiter.RESERVATION_POST + " guest-a", RateLimiter.RESERVATION_POST + " guest-b");
    }

    @Test
    void withoutAJwtPrincipalNothingIsConsumed() throws Exception {
        assertThat(preHandle(request("POST"))).isTrue();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("guest-a", "pw"));
        assertThat(preHandle(request("POST"))).isTrue();

        assertThat(consumed).isEmpty();
    }
}
