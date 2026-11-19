package ma.nuzul.inventory.adapter.out.persistence;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ma.nuzul.inventory.application.InventoryRepositoryPort;
import ma.nuzul.inventory.domain.InventoryChange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC on room_inventory (no JPA entity: the table is partitioned and only ever touched by guarded SQL).
 * Never writes {@code reserved}; the {@code inventory_no_oversell} CHECK stays untouched.
 */
@Repository
class JdbcInventoryRepository implements InventoryRepositoryPort {

    /*
     * One statement per batch. ORDER BY night makes THIS statement take its row locks in ascending night order, so
     * two concurrent batches over overlapping nights wait for each other instead of deadlocking. That only holds if
     * every writer of room_inventory MUST lock nights in ascending order too: story 1.4's reserve() and the expiry
     * decrement must lock through an ordered "SELECT ... ORDER BY night FOR NO KEY UPDATE" CTE to match. The
     * DO UPDATE ... WHERE skips a night whose new total would fall below reserved; it is absent from RETURNING.
     */
    static final String UPSERT_TOTALS = """
            INSERT INTO room_inventory (room_type_id, night, total)
            SELECT :roomTypeId, x.n, x.t
            FROM unnest(:nights::date[], :totals::int[]) AS x(n, t)
            ORDER BY x.n
            ON CONFLICT (room_type_id, night) DO UPDATE SET total = EXCLUDED.total
            WHERE room_inventory.reserved <= EXCLUDED.total
            RETURNING night
            """;

    private final JdbcClient jdbc;

    JdbcInventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<LocalDate> upsertTotals(UUID roomTypeId, List<InventoryChange> changes) {
        String[] nights = changes.stream().map(c -> c.night().toString()).toArray(String[]::new);
        Integer[] totals = changes.stream().map(InventoryChange::total).toArray(Integer[]::new);
        return new HashSet<>(jdbc.sql(UPSERT_TOTALS)
                .param("roomTypeId", roomTypeId)
                .param("nights", nights)
                .param("totals", totals)
                .query((rs, i) -> rs.getObject(1, LocalDate.class))
                .list());
    }
}
