package ma.nuzul.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Story 3.2: the search projection rejects shapes the index mapping cannot hold. */
class HotelDocumentTest {

    @Test
    void copiesItsCollections() {
        var amenities = new ArrayList<>(List.of("wifi"));
        var prices = new HashMap<>(Map.of("2026-12", BigDecimal.TEN));
        var document = new HotelDocument(UUID.randomUUID(), "Riad", "Fes", null, null, null, amenities, prices);

        amenities.add("pool");
        prices.clear();

        assertThat(document.amenities()).containsExactly("wifi");
        assertThat(document.minPriceByMonth()).containsOnlyKeys("2026-12");
    }

    @Test
    void requiresLatAndLonTogether() {
        assertThatThrownBy(() ->
                        new HotelDocument(UUID.randomUUID(), "Riad", "Fes", 34.0, null, null, List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lat and lon");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13", "2026-1", "26-12", "+2026-12", "december", "2026-12-01"})
    void rejectsAPriceKeyThatIsNotAMonth(String key) {
        assertThatThrownBy(() -> new HotelDocument(
                        UUID.randomUUID(), "Riad", "Fes", null, null, null, List.of(), Map.of(key, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM");
    }
}
