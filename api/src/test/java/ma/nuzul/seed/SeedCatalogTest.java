package ma.nuzul.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import ma.nuzul.seed.SeedCatalog.SeedHotel;
import ma.nuzul.seed.SeedCatalog.SeedRoomType;
import org.junit.jupiter.api.Test;

/** The demo data is the right size and every value passes the domain rules it will be sent through. */
class SeedCatalogTest {

    private final List<SeedHotel> hotels = SeedCatalog.hotels();

    @Test
    void sixCitiesOfTenUniquelyNamedHotels() {
        Map<String, Long> perCity =
                hotels.stream().collect(Collectors.groupingBy(SeedHotel::city, Collectors.counting()));

        assertThat(perCity)
                .containsOnlyKeys("Marrakech", "Fès", "Chefchaouen", "Essaouira", "Agadir", "Tanger")
                .allSatisfy((city, count) -> assertThat(count).isEqualTo(10));
        assertThat(hotels.stream().map(h -> h.city() + "|" + h.name()).distinct())
                .hasSize(60);
    }

    @Test
    void hotelsHaveValidStarsCoordinatesAndAmenities() {
        assertThat(hotels).allSatisfy(h -> {
            assertThat(h.stars()).isBetween(3, 5);
            assertThat(h.lat().scale()).isEqualTo(6);
            assertThat(h.lat().abs()).isLessThan(BigDecimal.valueOf(90));
            assertThat(h.lon().abs()).isLessThan(BigDecimal.valueOf(180));
            assertThat(h.address()).endsWith(h.city());
            assertThat(h.amenities()).contains("wifi").doesNotHaveDuplicates().hasSize(1 + h.stars());
        });
    }

    @Test
    void twoToFourRoomTypesPerHotelWithSellablePrices() {
        assertThat(hotels.stream().mapToInt(h -> h.roomTypes().size()).sum()).isEqualTo(180);
        assertThat(hotels).allSatisfy(h -> {
            assertThat(h.roomTypes()).hasSizeBetween(2, 4);
            assertThat(h.roomTypes().stream().map(SeedRoomType::name)).doesNotHaveDuplicates();
            assertThat(h.roomTypes()).allSatisfy(t -> {
                assertThat(t.rooms()).isPositive();
                assertThat(t.capacity()).isBetween(1, 20);
                assertThat(t.basePriceMad()).isPositive();
                assertThat(t.basePriceMad().scale()).isEqualTo(2);
            });
        });
    }

    @Test
    void fridayAndSaturdayNightsCostTwentyPercentMoreRoundedToTheDirham() {
        SeedRoomType type = new SeedRoomType("Suite", 2, null, 3, new BigDecimal("1005.00"), true);
        LocalDate thursday = LocalDate.of(2026, 11, 19);

        assertThat(type.priceOn(thursday)).isEqualByComparingTo("1005.00");
        assertThat(type.priceOn(thursday.plusDays(1))).isEqualTo(new BigDecimal("1206.00"));
        assertThat(type.priceOn(thursday.plusDays(2))).isEqualTo(new BigDecimal("1206.00"));
        assertThat(type.priceOn(thursday.plusDays(3))).isEqualByComparingTo("1005.00");
    }

    @Test
    void sameDataOnEveryCall() {
        assertThat(SeedCatalog.hotels()).isEqualTo(hotels);
    }
}
