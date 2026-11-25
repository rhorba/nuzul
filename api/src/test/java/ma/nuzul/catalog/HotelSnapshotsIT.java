package ma.nuzul.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Story 3.2: the read API the search indexer rebuilds documents from, against real Postgres. Hotels are inserted
 * with plain JDBC; the paging test uses ids that share one random high half, so other tests' hotels (random ids)
 * cannot fall between them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class HotelSnapshotsIT {

    @Autowired
    HotelSnapshots snapshots;

    @Autowired
    JdbcTemplate jdbc;

    private final String owner = "owner-" + UUID.randomUUID();

    private void hotel(UUID id, String status) {
        jdbc.update("""
                INSERT INTO hotel (id, owner_sub, name, city, stars, amenities, lat, lon, status)
                VALUES (?, ?, 'Riad Fes', 'Fes', 4, '{wifi,pool}', 34.064100, -4.973300, ?)
                """, id, owner, status);
    }

    private UUID roomType(UUID hotelId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO room_type (id, hotel_id, name, capacity) VALUES (?, ?, 'Double', 2)", id, hotelId);
        return id;
    }

    @Test
    void findReturnsAnActiveHotelWithItsRoomTypesAscending() {
        UUID h = UUID.randomUUID();
        hotel(h, "ACTIVE");
        UUID rt1 = roomType(h);
        UUID rt2 = roomType(h);
        roomType(otherHotel()); // another hotel's room type is not listed

        HotelSnapshot s = snapshots.find(h).orElseThrow();

        assertThat(s.id()).isEqualTo(h);
        assertThat(s.name()).isEqualTo("Riad Fes");
        assertThat(s.city()).isEqualTo("Fes");
        assertThat(s.stars()).isEqualTo(4);
        assertThat(s.amenities()).containsExactly("wifi", "pool");
        assertThat(s.lat()).isEqualTo(34.0641);
        assertThat(s.lon()).isEqualTo(-4.9733);
        assertThat(s.active()).isTrue();
        // Ascending as Postgres orders uuids (unsigned bytes), which java.util.UUID.compareTo does not match.
        assertThat(s.roomTypeIds())
                .containsExactlyInAnyOrder(rt1, rt2)
                .containsExactlyElementsOf(
                        jdbc.queryForList("SELECT id FROM room_type WHERE hotel_id = ? ORDER BY id", UUID.class, h));
    }

    private UUID otherHotel() {
        UUID other = UUID.randomUUID();
        hotel(other, "ACTIVE");
        return other;
    }

    @Test
    void findReturnsAnInactiveHotelAsInactive() {
        UUID h = UUID.randomUUID();
        hotel(h, "INACTIVE");

        HotelSnapshot s = snapshots.find(h).orElseThrow();

        assertThat(s.active()).isFalse();
        assertThat(s.roomTypeIds()).isEmpty();
    }

    @Test
    void findOfAHotelWithoutCoordinatesOrStarsHasNulls() {
        UUID h = UUID.randomUUID();
        jdbc.update("INSERT INTO hotel (id, owner_sub, name, city) VALUES (?, ?, 'Dar', 'Rabat')", h, owner);

        HotelSnapshot s = snapshots.find(h).orElseThrow();

        assertThat(s.lat()).isNull();
        assertThat(s.lon()).isNull();
        assertThat(s.stars()).isNull();
        assertThat(s.amenities()).isEmpty();
        assertThat(s.active()).isTrue();
    }

    @Test
    void findOfAnUnknownHotelIsEmpty() {
        assertThat(snapshots.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void activePageWalksActiveHotelsByIdSkippingInactiveOnes() {
        long high = UUID.randomUUID().getMostSignificantBits();
        UUID start = new UUID(high, 0);
        UUID h1 = new UUID(high, 1);
        UUID h2 = new UUID(high, 2);
        UUID retired = new UUID(high, 3);
        UUID h4 = new UUID(high, 4);
        UUID h5 = new UUID(high, 5);
        // Inserted out of order: the order comes from the id, not from insertion.
        hotel(h5, "ACTIVE");
        hotel(retired, "INACTIVE");
        hotel(h2, "ACTIVE");
        hotel(h4, "ACTIVE");
        hotel(h1, "ACTIVE");
        UUID rt = roomType(h4);

        List<HotelSnapshot> first = snapshots.activePage(start, 2);
        List<HotelSnapshot> second = snapshots.activePage(first.get(1).id(), 2);

        assertThat(first).extracting(HotelSnapshot::id).containsExactly(h1, h2);
        assertThat(second).extracting(HotelSnapshot::id).containsExactly(h4, h5);
        assertThat(second.get(0).roomTypeIds()).containsExactly(rt);
        assertThat(second.get(1).roomTypeIds()).isEmpty();
        assertThat(snapshots.activePage(start, 2)).isEqualTo(first);
    }

    @Test
    void theFirstPageStartsFromTheLowestActiveId() {
        hotel(UUID.randomUUID(), "ACTIVE");
        hotel(UUID.randomUUID(), "ACTIVE");

        List<HotelSnapshot> page = snapshots.activePage(null, 2);

        assertThat(page).hasSize(2).allSatisfy(s -> assertThat(s.active()).isTrue());
        UUID lowest =
                jdbc.queryForObject("SELECT id FROM hotel WHERE status = 'ACTIVE' ORDER BY id LIMIT 1", UUID.class);
        assertThat(page.get(0).id()).isEqualTo(lowest);
        assertThat(snapshots.activePage(page.get(0).id(), 1))
                .extracting(HotelSnapshot::id)
                .containsExactly(page.get(1).id());
    }

    @Test
    void aNonPositiveLimitIsRefused() {
        assertThatThrownBy(() -> snapshots.activePage(null, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
