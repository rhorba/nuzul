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
 * Plain JDBC on room_inventory (no JPA entity: the table is partitioned and only ever touched by guarded SQL). The
 * upsert never writes {@code reserved}; reserve never changes {@code total}; the {@code inventory_no_oversell} CHECK
 * stays untouched.
 */
@Repository
class JdbcInventoryRepository implements InventoryRepositoryPort {

    /*
     * One statement per batch. ORDER BY night makes THIS statement take its row locks in ascending night order, so
     * two concurrent batches over overlapping nights wait for each other instead of deadlocking. That only holds if
     * every writer of room_inventory locks rows in ascending (room_type_id, night) order too (database doc §3 rule 5):
     * RESERVE below and the expiry/cancel release lock through an ordered "SELECT ... ORDER BY night FOR NO KEY
     * UPDATE" CTE, and a transaction that touches several room types takes them in room_type_id order. The
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

    /*
     * The double-booking guard (system design, "Inventory logic"). One statement, one round trip:
     *  - The CTE locks the stay's rows in ascending night order (ORDER BY night FOR NO KEY UPDATE), the same order
     *    as UPSERT_TOTALS, so reserve and the bulk upsert (and two reserves) over overlapping nights wait for each
     *    other instead of deadlocking. A plain range UPDATE locks rows in whatever order its scan returns them.
     *  - A row lock that had to wait returns the latest committed version (including this transaction's own earlier
     *    writes). The guard and every non-key column of the new row come from those locked values (l), and the lock
     *    is held from the CTE through the UPDATE (and to commit), so nothing can change them in between.
     *  - r only locates the row to write, through the immutable key columns (room_type_id, night).
     *  - Why every non-key column, total included: under READ COMMITTED the target row is the version the statement
     *    snapshot saw. Postgres builds a provisional new row from that snapshot tuple plus the SET list and checks
     *    inventory_no_oversell on it BEFORE it detects the concurrent update and recomputes the row on the latest
     *    version. So the provisional row must already carry the real values: a column left to come from r would be
     *    stale there. total is therefore written back with the value held under our own lock; it is never changed.
     *    Because total is in every reserve's SET list, a future trigger or CDC filter on capacity changes must test
     *    OLD.total IS DISTINCT FROM NEW.total (or compare before/after values), never "UPDATE OF total".
     *  - Reproduced on PG16 (ReserveLockWaitIT): a guard on r refused a room released while we waited (false
     *    SOLD_OUT); SET from r.reserved, and then a total left to come from r after a concurrent raise, failed the
     *    CHECK with 23514 although the sale was valid.
     *  - A full night, or a night with no row at all, is simply not updated: the caller compares the count with the
     *    nights stayed and rolls back (SOLD_OUT). Nights that were incremented roll back with it. Known behaviour: a
     *    night inserted by a concurrent upsert that commits after this statement's snapshot is invisible to it, so
     *    the result is SOLD_OUT. That is inherent to READ COMMITTED, harmless, and only happens while inventory is
     *    being created.
     *  - The night range is repeated on r so the UPDATE is pruned to the stay's monthly partitions (ReservePlanIT).
     *  - inventory_no_oversell (reserved BETWEEN 0 AND total) stays the backstop; a violation (23514) is never caught.
     *  - Not the rejected "SELECT ... FOR UPDATE, then UPDATE" option: the lock and the guarded write are one
     *    statement, sent once, with no read-then-write gap in the application.
     * FOR NO KEY UPDATE (not FOR UPDATE): the key is not changed, so FK KEY SHARE lockers are not blocked.
     */
    static final String RESERVE = """
            WITH locked AS (
              SELECT night, reserved, total FROM room_inventory
              WHERE room_type_id = :roomTypeId AND night >= :checkIn AND night < :checkOut
              ORDER BY night
              FOR NO KEY UPDATE)
            UPDATE room_inventory r
               SET reserved = l.reserved + :rooms, total = l.total
              FROM locked l
             WHERE r.room_type_id = :roomTypeId AND r.night >= :checkIn AND r.night < :checkOut
               AND r.night = l.night
               AND l.reserved + :rooms <= l.total
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

    @Override
    public int reserve(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        return jdbc.sql(RESERVE)
                .param("roomTypeId", roomTypeId)
                .param("checkIn", checkIn)
                .param("checkOut", checkOut)
                .param("rooms", rooms)
                .update();
    }
}
