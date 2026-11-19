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
 * only when {@code reserved <= newTotal}; refused nights are left out of the returned set. There is no rollback
 * here, the IT proves that.
 */
class InMemoryInventoryRepository implements InventoryRepositoryPort {

    record Row(int total, int reserved) {}

    final Map<UUID, Map<LocalDate, Row>> rows = new TreeMap<>();
    int upsertCalls;

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
}
