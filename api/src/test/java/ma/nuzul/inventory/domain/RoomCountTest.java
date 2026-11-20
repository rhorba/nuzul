package ma.nuzul.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One reservation books 1..9 rooms of one room type. */
class RoomCountTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 9})
    void oneToNineIsAccepted(int rooms) {
        assertThat(RoomCount.requireValid(rooms)).isEqualTo(rooms);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 10, Integer.MAX_VALUE})
    void outsideOneToNineIsRefused(int rooms) {
        Throwable t = catchThrowable(() -> RoomCount.requireValid(rooms));

        assertThat(t).isInstanceOf(DomainValidationException.class);
        assertThat(((DomainValidationException) t).code()).isEqualTo(DomainValidationException.VALIDATION_FAILED);
    }

    @Test
    void boundsAreOneAndNine() {
        assertThat(RoomCount.MIN).isEqualTo(1);
        assertThat(RoomCount.MAX).isEqualTo(9);
    }
}
