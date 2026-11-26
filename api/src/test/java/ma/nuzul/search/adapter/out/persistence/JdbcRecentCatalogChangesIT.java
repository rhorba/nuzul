package ma.nuzul.search.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Story 3.2: the reindex catch-up query on real {@code outbox_event} rows. Rows are inserted already published, so no
 * relay run in this shared context ever sends them; every test asserts on its own fresh ids only.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc // same cached context as the other MockMvc ITs
class JdbcRecentCatalogChangesIT {

    @Autowired
    JdbcRecentCatalogChanges changes;

    @Autowired
    JdbcTemplate jdbc;

    /** One row, created {@code secondsFromNow} relative to the database clock. */
    private void row(String aggregate, String payload, int secondsFromNow) {
        jdbc.update("""
                INSERT INTO outbox_event (id, aggregate, aggregate_id, type, payload, created_at, published_at)
                VALUES (?, ?, ?, 'HotelChanged', ?::jsonb, now() + make_interval(secs => ?), now())
                """, UUID.randomUUID(), aggregate, UUID.randomUUID(), payload, secondsFromNow);
    }

    private static String hotel(UUID hotelId) {
        return "{\"hotelId\":\"%s\"}".formatted(hotelId);
    }

    @Test
    void returnsEachRecentlyChangedHotelOnceFromBothCatalogAggregates() {
        Instant since = jdbc.queryForObject("SELECT now() - interval '10 seconds'", Timestamp.class)
                .toInstant();
        UUID edited = UUID.randomUUID();
        UUID legacy = UUID.randomUUID();
        row("hotel", hotel(edited), 0);
        row("hotel", hotel(edited), 1);
        row("room_type", "{\"roomTypeId\":\"%s\",\"hotelId\":\"%s\"}".formatted(UUID.randomUUID(), legacy), 0);

        assertThat(changes.hotelIdsChangedSince(since)).containsOnlyOnce(edited).contains(legacy);
    }

    @Test
    void skipsOlderRowsOtherAggregatesAndPayloadsWithoutAUsableHotelId() {
        Instant since = jdbc.queryForObject("SELECT now() - interval '10 seconds'", Timestamp.class)
                .toInstant();
        UUID old = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        row("hotel", hotel(old), -60);
        row("reservation", hotel(reservation), 0);
        row("hotel", "{\"hotelId\":\"not-a-uuid\"}", 0);
        row("hotel", "{\"roomTypeId\":\"%s\"}".formatted(UUID.randomUUID()), 0);

        assertThat(changes.hotelIdsChangedSince(since)).doesNotContain(old, reservation);
    }
}
