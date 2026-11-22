package ma.nuzul.reservation.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;

/** Out-port for reservation rows. */
public interface ReservationRepositoryPort {

    /**
     * Inserts the reservation unless the guest already has one with its idempotency key. When another transaction
     * is inserting that key right now, waits for it to finish (Postgres unique index): false once it committed, and
     * this insert goes ahead if it rolled back.
     *
     * @return true when inserted, false when the (guest, key) pair already exists
     */
    boolean insertIfKeyAbsent(Reservation reservation);

    Optional<Reservation> findByIdempotencyKey(String guestSub, UUID idempotencyKey);

    /** The reservation, only when it belongs to {@code guestSub}: another guest's id reads as unknown (no IDOR). */
    Optional<Reservation> findForGuest(UUID id, String guestSub);

    Optional<ReservationStatus> findStatus(UUID id);

    /**
     * HELD → CONFIRMED, only while the hold has not expired at {@code now}: one conditional UPDATE, so a confirm and
     * the expiry job can never both win. hold_expires_at is kept (a key replay still answers with it).
     *
     * @return true when this call confirmed it
     */
    boolean confirmIfHeld(UUID id, Instant now);

    /**
     * {@code from} -> CANCELLED, only if the reservation is still in {@code from}: one conditional UPDATE, so cancel,
     * confirm and expiry never both win.
     *
     * @return true when this call cancelled it
     */
    boolean cancelIf(UUID id, ReservationStatus from);

    /**
     * Ids of HELD reservations whose hold ended at or before {@code now}, oldest first, at most {@code limit} (partial
     * index reservation_expiry). A plain read: each id is re-checked under its row lock by {@link #lockExpiredHold}.
     */
    List<UUID> expiredHoldIds(Instant now, int limit);

    /**
     * Locks the reservation row ({@code FOR UPDATE SKIP LOCKED}) if it is still HELD with its hold ended at
     * {@code now}. Empty when it is not, or when another transaction (a confirm, a cancel, another expiry run) holds
     * the row: that one decides, and the next run looks again if it is still due.
     */
    Optional<Reservation> lockExpiredHold(UUID id, Instant now);

    /**
     * HELD → EXPIRED, only if still HELD: one conditional UPDATE, like confirm and cancel. hold_expires_at is kept.
     *
     * @return true when this call expired it
     */
    boolean expireIfHeld(UUID id);
}
