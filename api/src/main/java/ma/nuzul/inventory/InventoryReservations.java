package ma.nuzul.inventory;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Inventory's published API for other modules: the double-booking guard (system design, "Inventory logic"). Story
 * 2.1's hold flow calls it inside its own transaction; it is never exposed over HTTP.
 *
 * <p>Takes plain values, not a domain type, so inventory's domain package stays internal to the module.
 */
public interface InventoryReservations {

    /**
     * Adds {@code rooms} to {@code reserved} on every night of the half-open stay [checkIn, checkOut), all or nothing,
     * with one guarded statement. Never changes {@code total}.
     *
     * <p>Must join the caller's transaction (propagation MANDATORY: no active transaction throws
     * IllegalTransactionStateException). On SOLD_OUT some nights may already be incremented by the statement, so the
     * caller must let the exception roll its transaction back; this method marks it rollback-only.
     *
     * <p>DomainValidationException is thrown through the same transactional proxy, so it also marks the caller's
     * transaction rollback-only: callers validate their input (stay, rooms) before they start the transaction.
     *
     * <p>A deadlock or serialization failure (SQLSTATE 40P01/40001) propagates unchanged. Any retry must wrap the
     * caller's outer {@code @Transactional} method, so the whole transaction is replayed; never retry
     * {@code reserve()} itself, because the transaction it joined is already rolled back.
     *
     * @param checkIn first night stayed
     * @param checkOut the day the guest leaves, not a night stayed; 1..30 nights after checkIn
     * @param rooms 1..9
     * @throws SoldOutException SOLD_OUT (409) when any night is missing or has fewer than {@code rooms} left
     * @throws ma.nuzul.shared.error.DomainValidationException VALIDATION_FAILED for an invalid stay or room count
     */
    void reserve(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms);
}
