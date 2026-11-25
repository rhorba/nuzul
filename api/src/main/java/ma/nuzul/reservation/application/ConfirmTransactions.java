package ma.nuzul.reservation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import ma.nuzul.payment.Payments;
import ma.nuzul.reservation.ReservationEvent;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import ma.nuzul.shared.outbox.OutboxPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two short transactions around the provider call (which runs in neither): start the payment, then confirm the
 * reservation and capture the payment together.
 */
@Component
class ConfirmTransactions {

    private final ReservationRepositoryPort reservations;
    private final Payments payments;
    private final OutboxPort outbox;
    private final Clock clock;

    ConfirmTransactions(ReservationRepositoryPort reservations, Payments payments, OutboxPort outbox, Clock clock) {
        this.reservations = reservations;
        this.payments = payments;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** The reservation's active payment: a new PENDING one, or the one a concurrent or earlier confirm started. */
    @Transactional
    Payments.Active start(Reservation reservation) {
        return payments.begin(reservation.id(), reservation.totalMad());
    }

    /**
     * Confirms the reservation if it is still HELD and unexpired now (read inside this transaction), and captures the
     * payment in the same commit. Nothing is written when either step fails: a payment already claimed by a refund
     * rolls the confirmation back, so a refunded payment never pays for a CONFIRMED reservation. The
     * {@code ReservationConfirmed} event is appended only after both succeeded, in the same commit.
     *
     * @param reservation the reservation as the caller read it: its id, and the stay facts the event carries (none of
     *     them change after the hold)
     * @return true when this call confirmed it
     */
    @Transactional
    boolean finish(Reservation reservation, UUID paymentId, String providerRef) {
        if (!reservations.confirmIfHeld(reservation.id(), Instant.now(clock))) {
            return false;
        }
        if (!payments.capture(paymentId, providerRef)) {
            throw new CaptureLost(); // rolls the CONFIRMED back
        }
        ReservationEvents.append(outbox, clock, reservation, ReservationStatus.CONFIRMED, ReservationEvent.CONFIRMED);
        return true;
    }

    /** A concurrent refund claimed the payment first; thrown so that this transaction rolls back. */
    static final class CaptureLost extends RuntimeException {
        CaptureLost() {
            super("payment no longer PENDING: claimed by a refund", null, false, false);
        }
    }
}
