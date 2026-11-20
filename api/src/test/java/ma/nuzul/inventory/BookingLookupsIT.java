package ma.nuzul.inventory;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.catalog.BookableRoomTypes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Story 2.1 lookups the hold uses: the bookable hotel of a room type, and the SQL price of a stay. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BookingLookupsIT {

    @Autowired
    BookableRoomTypes bookable;

    @Autowired
    StayPricing pricing;

    @Autowired
    JdbcTemplate jdbc;

    private ReserveFixtures fx;

    private ReserveFixtures fx() {
        if (fx == null) {
            fx = new ReserveFixtures(jdbc);
        }
        return fx;
    }

    @Test
    void hotelOfAnActiveHotelsRoomType() {
        UUID roomTypeId = fx().roomType("owner-" + UUID.randomUUID());
        UUID hotelId = jdbc.queryForObject("SELECT hotel_id FROM room_type WHERE id = ?", UUID.class, roomTypeId);

        assertThat(bookable.hotelOf(roomTypeId)).contains(hotelId);
    }

    @Test
    void inactiveHotelsAndUnknownRoomTypesAreNotBookable() {
        UUID roomTypeId = fx().roomType("owner-" + UUID.randomUUID());
        fx().deactivateHotelOf(roomTypeId);

        assertThat(bookable.hotelOf(roomTypeId)).isEmpty();
        assertThat(bookable.hotelOf(UUID.randomUUID())).isEmpty();
        assertThat(bookable.hotelOf(null)).isEmpty();
    }

    @Test
    void priceSumsTheStayNightsInNumeric() {
        UUID roomTypeId = fx().roomType("owner-" + UUID.randomUUID());
        fx().rate(roomTypeId, night(0), "650.10");
        fx().rate(roomTypeId, night(1), "780.25");
        fx().rate(roomTypeId, night(2), "1.00");

        assertThat(pricing.price(roomTypeId, night(0), night(2), 3)).isEqualTo(new BigDecimal("4291.05"));
    }

    @Test
    void aMissingRateIsSoldOut() {
        UUID roomTypeId = fx().roomType("owner-" + UUID.randomUUID());
        fx().rate(roomTypeId, night(0), "650.00");

        assertThatThrownBy(() -> pricing.price(roomTypeId, night(0), night(2), 1))
                .isInstanceOf(SoldOutException.class);
    }
}
