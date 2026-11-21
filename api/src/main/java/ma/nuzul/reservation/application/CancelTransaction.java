package ma.nuzul.reservation.application;

import java.time.Clock;
import java.time.Instant;
import ma.nuzul.inventory.InventoryReservations;
import ma.nuzul.payment.Payments;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One short transaction, three writes that must commit together (database doc section 3, rule 4 applies to cancel
 * as to expiry):
 *
 * <ol>
 *   <li>a conditional status change {@code HELD|CONFIRMED -> CANCELLED} from the status the caller read, so a cancel
 *       racing a confirm, another cancel or the expiry job never wins twice;
 *   <li>the rooms released (inventory rows locked in ascending night order, after the reservation row);
 *   <li>for a CONFIRMED stay still inside its free-cancellation window, its payment claimed for a refund.
 * </ol>
 *
 * The provider is called only after commit (no external call inside a transaction).
 */
@Component
class CancelTransaction {

    private static final Logger log = LoggerFactory.getLogger(CancelTransaction.class);

    private final ReservationRepositoryPort reservations;
    private final InventoryReservations inventory;
    private final Payments payments;
    private final Clock clock;

    CancelTransaction(
            ReservationRepositoryPort reservations, InventoryReservations inventory, Payments payments, Clock clock) {
        this.reservations = reservations;
        this.inventory = inventory;
        this.payments = payments;
        this.clock = clock;
    }

    /** @return false when the status changed since it was read: nothing was written */
    @Transactional
    boolean cancel(Reservation reservation) {
        if (!reservations.cancelIf(reservation.id(), reservation.status())) {
            return false;
        }
        inventory.release(reservation.roomTypeId(), reservation.checkIn(), reservation.checkOut(), reservation.rooms());
        boolean free = reservation.status() == ReservationStatus.CONFIRMED
                && reservation.cancellationPolicy().isFreeAt(Instant.now(clock));
        if (free && payments.claimRefund(reservation.id()).isEmpty()) {
            // CONFIRMED implies a CAPTURED payment (they commit together): this is inconsistent data, never silent.
            log.error(
                    "Free cancellation of CONFIRMED reservation {} found no captured payment to refund",
                    reservation.id());
        }
        return true;
    }
}
