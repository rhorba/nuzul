package ma.nuzul.inventory.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ma.nuzul.inventory.domain.InventoryChange;

/**
 * Out-port for per-night room inventory. Never writes {@code reserved} outside the reservation flow; the
 * {@code inventory_no_oversell} CHECK stays the last line of defence.
 */
public interface InventoryRepositoryPort {

    /**
     * Inserts each night, or updates {@code total} on an existing night only when {@code reserved <= total}. Must be
     * called inside a transaction: the caller rolls back when a night was refused.
     *
     * @return the nights actually written; a requested night missing from it was refused (total below reserved)
     */
    Set<LocalDate> upsertTotals(UUID roomTypeId, List<InventoryChange> changes);
}
