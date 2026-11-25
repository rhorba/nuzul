package ma.nuzul.shared.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC over {@code outbox_event}. The batch read and the gauges use the partial index
 * {@code outbox_unpublished (created_at) WHERE published_at IS NULL}; the nightly retention delete scans the table,
 * which a week of events keeps small.
 */
@Repository
class JdbcOutboxRelayStore implements OutboxRelayStore {

    /** {@code id} breaks created_at ties (same-transaction appends share now()), so the order is total and stable. */
    static final String LOCK_BATCH = """
            SELECT id, aggregate, aggregate_id, type, payload::text AS payload
            FROM outbox_event
            WHERE published_at IS NULL AND attempts < :maxAttempts
            ORDER BY created_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """;

    static final String MARK_PUBLISHED = "UPDATE outbox_event SET published_at = now() WHERE id = :id";

    static final String MARK_FAILED = """
            UPDATE outbox_event SET attempts = attempts + 1, last_error = :error
            WHERE id = :id
            RETURNING attempts
            """;

    static final String LAG_SECONDS = """
            SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)::float8
            FROM outbox_event
            WHERE published_at IS NULL AND attempts < :maxAttempts
            """;

    static final String PARKED_COUNT =
            "SELECT count(*) FROM outbox_event WHERE published_at IS NULL AND attempts >= :maxAttempts";

    static final String DELETE_PUBLISHED_BEFORE =
            "DELETE FROM outbox_event WHERE published_at < now() - make_interval(secs => :seconds)";

    private final JdbcClient jdbc;

    JdbcOutboxRelayStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OutboxRow> lockBatch(int maxAttempts, int batchSize) {
        return jdbc.sql(LOCK_BATCH)
                .param("maxAttempts", maxAttempts)
                .param("batchSize", batchSize)
                .query((rs, n) -> new OutboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("type"),
                        rs.getString("payload")))
                .list();
    }

    @Override
    public void markPublished(UUID id) {
        jdbc.sql(MARK_PUBLISHED).param("id", id).update();
    }

    @Override
    public int markFailed(UUID id, String error) {
        return jdbc.sql(MARK_FAILED)
                .param("id", id)
                .param("error", error)
                .query(Integer.class)
                .single();
    }

    @Override
    public double lagSeconds(int maxAttempts) {
        return jdbc.sql(LAG_SECONDS)
                .param("maxAttempts", maxAttempts)
                .query(Double.class)
                .single();
    }

    @Override
    public long parkedCount(int maxAttempts) {
        return jdbc.sql(PARKED_COUNT)
                .param("maxAttempts", maxAttempts)
                .query(Long.class)
                .single();
    }

    @Override
    public int deletePublishedBefore(Duration retention) {
        return jdbc.sql(DELETE_PUBLISHED_BEFORE)
                .param("seconds", retention.toMillis() / 1000.0)
                .update();
    }
}
