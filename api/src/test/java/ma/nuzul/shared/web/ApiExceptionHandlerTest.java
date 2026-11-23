package ma.nuzul.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ma.nuzul.shared.error.ConflictException;
import ma.nuzul.shared.error.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * Any ConflictException (including a module's subclass, e.g. SOLD_OUT) is a 409 problem carrying its code; a
 * TooManyRequestsException is a 429 that tells the client when to retry, when it knows.
 */
class ApiExceptionHandlerTest {

    @Test
    void conflictIs409WithItsCodeDetailAndProperties() {
        var problem = new ApiExceptionHandler()
                .handleConflict(new ConflictException("SOME_CONFLICT", "it conflicts", Map.of("nights", List.of())));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getDetail()).isEqualTo("it conflicts");
        assertThat(problem.getProperties())
                .containsEntry(ApiExceptionHandler.CODE, "SOME_CONFLICT")
                .containsEntry("nights", List.of());
    }

    @Test
    void tooManyRequestsIs429WithCodeAndRetryAfterHeader() {
        var response = new ApiExceptionHandler()
                .handleTooManyRequests(new TooManyRequestsException("RATE_LIMITED", "slow down", 42));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
        var problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(429);
        assertThat(problem.getDetail()).isEqualTo("slow down");
        assertThat(problem.getProperties()).containsEntry(ApiExceptionHandler.CODE, "RATE_LIMITED");
    }

    @Test
    void tooManyRequestsWithoutRetryAfterHasNoHeader() {
        var response = new ApiExceptionHandler()
                .handleTooManyRequests(new TooManyRequestsException("TOO_MANY_ACTIVE_HOLDS", "pay or cancel first"));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER))
                .isFalse();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry(ApiExceptionHandler.CODE, "TOO_MANY_ACTIVE_HOLDS");
    }
}
