package ma.nuzul.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Domain invariants hold without Spring or the database. */
class HotelTest {

    private static Hotel hotel(String name, String city, Integer stars, BigDecimal lat, BigDecimal lon) {
        return hotel("owner-a", name, city, stars, List.of(), lat, lon, HotelStatus.ACTIVE);
    }

    private static Hotel hotel(
            String owner,
            String name,
            String city,
            Integer stars,
            List<String> amenities,
            BigDecimal lat,
            BigDecimal lon,
            HotelStatus status) {
        return new Hotel(
                UUID.randomUUID(), owner, name, city, null, stars, amenities, lat, lon, status, Instant.now(), null);
    }

    @Test
    void validHotelIsBuilt() {
        var h = hotel("Riad", "Fes", 4, new BigDecimal("34.0"), new BigDecimal("-5.0"));

        assertThat(h.name()).isEqualTo("Riad");
        assertThat(h.stars()).isEqualTo(4);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankNameIsRejected(String name) {
        assertThatThrownBy(() -> hotel(name, "Fes", null, null, null))
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).code())
                .isEqualTo("VALIDATION_FAILED");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void blankCityIsRejected(String city) {
        assertThatThrownBy(() -> hotel("Riad", city, null, null, null)).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1})
    void starsOutsideOneToFiveAreRejected(int stars) {
        assertThatThrownBy(() -> hotel("Riad", "Fes", stars, null, null)).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-90.000001", "90.1"})
    void latitudeOutOfRangeIsRejected(String lat) {
        assertThatThrownBy(() -> hotel("Riad", "Fes", null, new BigDecimal(lat), null))
                .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-180.5", "180.000001"})
    void longitudeOutOfRangeIsRejected(String lon) {
        assertThatThrownBy(() -> hotel("Riad", "Fes", null, null, new BigDecimal(lon)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void boundaryCoordinatesAndStarsAreAccepted() {
        assertThat(hotel("Riad", "Fes", 1, new BigDecimal("-90"), new BigDecimal("180")))
                .isNotNull();
        assertThat(hotel("Riad", "Fes", 5, new BigDecimal("90"), new BigDecimal("-180")))
                .isNotNull();
    }

    @Test
    void blankOwnerOrMissingStatusIsRejected() {
        assertThatThrownBy(() -> hotel(" ", "Riad", "Fes", null, null, null, null, HotelStatus.ACTIVE))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> hotel("owner-a", "Riad", "Fes", null, null, null, null, null))
                .isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void blankAmenityIsRejected(String amenity) {
        assertThatThrownBy(() ->
                        hotel("owner-a", "Riad", "Fes", null, List.of("wifi", amenity), null, null, HotelStatus.ACTIVE))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void nullAmenityIsRejectedAsValidationNotNpe() {
        var withNull = new ArrayList<String>();
        withNull.add("wifi");
        withNull.add(null);

        assertThatThrownBy(() -> hotel("owner-a", "Riad", "Fes", null, withNull, null, null, HotelStatus.ACTIVE))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void amenitiesAreDefensivelyCopiedAndNullBecomesEmpty() {
        var source = new ArrayList<>(List.of("wifi"));
        var h = hotel("owner-a", "Riad", "Fes", null, source, null, null, HotelStatus.ACTIVE);
        source.add("pool");

        assertThat(h.amenities()).containsExactly("wifi");
        assertThat(hotel("owner-a", "Riad", "Fes", null, null, null, null, HotelStatus.ACTIVE)
                        .amenities())
                .isEmpty();
    }
}
