package ma.nuzul.reservation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import ma.nuzul.inventory.InventoryReservations;
import ma.nuzul.reservation.ReservationEvent;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import ma.nuzul.shared.outbox.OutboxPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One reservation, one short transaction: lock the row if it is still a due hold (skipping a row another transaction
 * holds), HELD → EXPIRED, release its rooms. The status change and the release commit together or not at all, and the
 * row lock comes before the inventory locks, in the same order as cancel, so the two cannot deadlock. The
 * {@code ReservationExpired} event is appended in the same transaction, only for a hold this call expired.
 */
@Component
class ExpiryTransaction {

    private final ReservationRepositoryPort reservations;
    private final InventoryReservations inventory;
    private final OutboxPort outbox;
    private final Clock clock;

    ExpiryTransaction(
            ReservationRepositoryPort reservations, InventoryReservations inventory, OutboxPort outbox, Clock clock) {
        this.reservations = reservations;
        this.inventory = inventory;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** @return false when the reservation is no longer a due hold, or is locked by someone else: nothing written */
    @Transactional
    boolean expire(UUID id, Instant now) {
        Reservation hold = reservations.lockExpiredHold(id, now).orElse(null);
        if (hold == null || !reservations.expireIfHeld(id)) {
            return false;
        }
        inventory.release(hold.roomTypeId(), hold.checkIn(), hold.checkOut(), hold.rooms());
        ReservationEvents.append(outbox, clock, hold, ReservationStatus.EXPIRED, ReservationEvent.EXPIRED);
        return true;
    }
}
