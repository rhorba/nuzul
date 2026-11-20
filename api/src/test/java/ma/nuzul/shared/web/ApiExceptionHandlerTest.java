package ma.nuzul.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import ma.nuzul.shared.error.ConflictException;
import org.junit.jupiter.api.Test;

/** Any ConflictException (including a module's subclass, e.g. SOLD_OUT) is a 409 problem carrying its code. */
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
}
