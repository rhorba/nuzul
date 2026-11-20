package ma.nuzul.inventory.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ma.nuzul.inventory.domain.InventoryChange;

/**
 * Out-port for per-night room inventory. Only {@link #reserve} writes {@code reserved}; the
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

    /**
     * Adds {@code rooms} to {@code reserved} on each night of [checkIn, checkOut) that exists and has that many rooms
     * left, in one statement. Never changes {@code total}. Must be called inside a transaction: the caller rolls back
     * when fewer nights than stayed were updated.
     *
     * @return the number of nights updated; fewer than the nights stayed means a night is missing or full
     */
    int reserve(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms);

    /** The nights of this room type that have an inventory row in [from, toExclusive), any total. */
    Set<LocalDate> stockedNights(UUID roomTypeId, LocalDate from, LocalDate toExclusive);
}
