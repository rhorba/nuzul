package ma.nuzul.search.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.nuzul.search.application.RecentCatalogChangesPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@link RecentCatalogChangesPort} on {@code outbox_event}, published rows included (the relay may already have sent
 * them; the listener's write then went to the old index). Both catalog aggregates: {@code hotel}, and the legacy
 * {@code room_type} rows, whose payloads carry the hotel id too.
 *
 * <p>The regex filter skips a payload whose {@code hotelId} is missing or not a UUID instead of failing the cast; the
 * WHERE clause runs before the projection, so the cast only sees matching rows. No index covers {@code created_at} on
 * published rows, so this scans the table; outbox retention (7 days by default) keeps it small, and it runs once per
 * reindex.
 */
@Component
class JdbcRecentCatalogChanges implements RecentCatalogChangesPort {

    static final String HOTEL_IDS_SINCE = """
            SELECT DISTINCT (payload ->> 'hotelId')::uuid AS hotel_id
            FROM outbox_event
            WHERE aggregate IN ('hotel', 'room_type')
              AND created_at >= :since
              AND payload ->> 'hotelId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            ORDER BY hotel_id
            """;

    private final JdbcClient jdbc;

    JdbcRecentCatalogChanges(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** clock_timestamp(), not now(): the wall clock at this statement, as the outbox stamps its rows. */
    @Override
    public Instant databaseNow() {
        return jdbc.sql("SELECT clock_timestamp()")
                .query(Timestamp.class)
                .single()
                .toInstant();
    }

    @Override
    public List<UUID> hotelIdsChangedSince(Instant since) {
        return jdbc.sql(HOTEL_IDS_SINCE)
                .param("since", Timestamp.from(since))
                .query((rs, row) -> rs.getObject("hotel_id", UUID.class))
                .list();
    }
}
