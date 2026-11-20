package ma.nuzul.inventory.application;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import ma.nuzul.inventory.domain.InventoryChange;

/**
 * Test fake for {@link InventoryRepositoryPort}. Mirrors the SQL guard: a night is inserted, or its total updated
 * only when {@code reserved <= newTotal}; refused nights are left out of the returned set. reserve() increments the
 * nights that have room left and returns how many. There is no rollback here, the ITs prove that.
 */
class InMemoryInventoryRepository implements InventoryRepositoryPort {

    record Row(int total, int reserved) {}

    final Map<UUID, Map<LocalDate, Row>> rows = new TreeMap<>();
    int upsertCalls;
    int reserveCalls;

    void seed(UUID roomTypeId, LocalDate night, int total, int reserved) {
        rows.computeIfAbsent(roomTypeId, k -> new TreeMap<>()).put(night, new Row(total, reserved));
    }

    Row row(UUID roomTypeId, LocalDate night) {
        return rows.getOrDefault(roomTypeId, Map.of()).get(night);
    }

    @Override
    public Set<LocalDate> upsertTotals(UUID roomTypeId, List<InventoryChange> changes) {
        upsertCalls++;
        var nights = rows.computeIfAbsent(roomTypeId, k -> new TreeMap<>());
        Set<LocalDate> applied = new HashSet<>();
        for (InventoryChange c : changes) {
            Row current = nights.get(c.night());
            if (current == null || current.reserved() <= c.total()) {
                nights.put(c.night(), new Row(c.total(), current == null ? 0 : current.reserved()));
                applied.add(c.night());
            }
        }
        return applied;
    }

    /** Mirrors the guarded UPDATE: each existing night with room left is incremented; the count is returned. */
    @Override
    public int reserve(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        reserveCalls++;
        var nights = rows.getOrDefault(roomTypeId, Map.of());
        int updated = 0;
        for (LocalDate night : checkIn.datesUntil(checkOut).toList()) {
            Row current = nights.get(night);
            if (current != null && current.reserved() + rooms <= current.total()) {
                nights.put(night, new Row(current.total(), current.reserved() + rooms));
                updated++;
            }
        }
        return updated;
    }
}
