package ma.nuzul.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import ma.nuzul.shared.error.ConflictException;
import org.junit.jupiter.api.Test;

/** SOLD_OUT is a ConflictException (so the shared handler answers 409 problem+json) and unchecked (rolls back). */
class SoldOutExceptionTest {

    @Test
    void isAnUncheckedConflictWithCodeSoldOut() {
        var e = new SoldOutException();

        assertThat(e).isInstanceOf(ConflictException.class).isInstanceOf(RuntimeException.class);
        assertThat(e.code()).isEqualTo(SoldOutException.SOLD_OUT).isEqualTo("SOLD_OUT");
        assertThat(e.getMessage()).isEqualTo("Not enough rooms left for the requested nights");
        assertThat(e.properties()).isEmpty();
    }
}
