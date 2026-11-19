package ma.nuzul.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Room-type invariants hold without Spring or the database. */
class RoomTypeTest {

    private static RoomType roomType(String name, int capacity) {
        return new RoomType(UUID.randomUUID(), UUID.randomUUID(), name, capacity, "1 double");
    }

    @Test
    void validRoomTypeIsBuilt() {
        var r = roomType("Double", 2);

        assertThat(r.name()).isEqualTo("Double");
        assertThat(r.capacity()).isEqualTo(2);
        assertThat(r.bedConfig()).isEqualTo("1 double");
    }

    @Test
    void bedConfigIsOptional() {
        assertThat(new RoomType(UUID.randomUUID(), UUID.randomUUID(), "Single", 1, null).bedConfig())
                .isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankNameIsRejected(String name) {
        assertThatThrownBy(() -> roomType(name, 2))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).code())
                .isEqualTo("VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void capacityBelowOneIsRejected(int capacity) {
        assertThatThrownBy(() -> roomType("Double", capacity)).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {21, 32768, 65537})
    void capacityAboveMaxIsRejected(int capacity) {
        assertThatThrownBy(() -> roomType("Double", capacity)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void capacityAtMaxIsAccepted() {
        assertThat(roomType("Dorm", RoomType.MAX_CAPACITY).capacity()).isEqualTo(20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankBedConfigIsStoredAsNull(String bedConfig) {
        assertThat(new RoomType(UUID.randomUUID(), UUID.randomUUID(), "Single", 1, bedConfig).bedConfig())
                .isNull();
    }

    @Test
    void missingIdOrHotelIdIsRejected() {
        assertThatThrownBy(() -> new RoomType(null, UUID.randomUUID(), "Double", 2, null))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new RoomType(UUID.randomUUID(), null, "Double", 2, null))
                .isInstanceOf(DomainValidationException.class);
    }
}
