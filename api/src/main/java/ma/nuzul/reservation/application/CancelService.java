package ma.nuzul.reservation.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.payment.Payments;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import ma.nuzul.shared.error.ConflictException;
import ma.nuzul.shared.error.DomainValidationException;
import ma.nuzul.shared.error.GoneException;
import ma.nuzul.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cancels through {@link CancelTransaction}, then sends a claimed refund to the provider after commit. A provider error
 * is not the guest's problem: the reservation is cancelled either way, the answer says the refund is PENDING, and the
 * next cancel call (or reconciliation) sends it again.
 */
@Service
class CancelService implements CancelReservationUseCase {

    static final String RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND";
    static final String HOLD_EXPIRED = "HOLD_EXPIRED";
    static final String CONCURRENT_UPDATE = "CONCURRENT_UPDATE";
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final Logger log = LoggerFactory.getLogger(CancelService.class);

    private final ReservationRepositoryPort reservations;
    private final CancelTransaction transaction;
    private final Payments payments;

    CancelService(ReservationRepositoryPort reservations, CancelTransaction transaction, Payments payments) {
        this.reservations = reservations;
        this.transaction = transaction;
        this.payments = payments;
    }

    @Override
    public Cancelled cancel(String guestSub, UUID reservationId) {
        if (guestSub == null || reservationId == null) {
            throw new DomainValidationException("reservation id is required");
        }
        // Two passes at most: a lost race re-reads the status the winner left (CANCELLED, CONFIRMED or EXPIRED).
        for (int attempt = 0; attempt < 2; attempt++) {
            Reservation reservation = reservations
                    .findForGuest(reservationId, guestSub)
                    .orElseThrow(() -> new NotFoundException(
                            RESERVATION_NOT_FOUND, "Reservation " + reservationId + " not found"));
            switch (reservation.status()) {
                case CANCELLED -> {
                    return outcome(reservationId);
                }
                case EXPIRED -> throw new GoneException(HOLD_EXPIRED, "The hold already expired");
                case HELD, CONFIRMED -> {
                    if (transaction.cancel(reservation)) {
                        return outcome(reservationId); // sends the refund the transaction claimed, if any
                    }
                }
            }
        }
        // Lost two races in one call (e.g. HELD -> CONFIRMED, then CONFIRMED -> CANCELLED by another cancel): never
        // guess the outcome, ask for a retry, which answers from the settled state.
        throw new ConflictException(
                CONCURRENT_UPDATE, "The reservation changed while it was being cancelled; retry", Map.of());
    }

    /** The cancellation as it stands, finishing a refund the provider has not confirmed yet. */
    private Cancelled outcome(UUID reservationId) {
        Optional<Payments.Refund> refund = payments.refundOf(reservationId);
        if (refund.isPresent() && !refund.get().done()) {
            send(refund.get().paymentId());
            refund = payments.refundOf(reservationId);
        }
        return refund.map(r -> new Cancelled(
                        reservationId,
                        ReservationStatus.CANCELLED,
                        r.done() ? RefundStatus.COMPLETED : RefundStatus.PENDING,
                        r.amountMad()))
                .orElseGet(() -> new Cancelled(reservationId, ReservationStatus.CANCELLED, RefundStatus.NONE, ZERO));
    }

    private void send(UUID paymentId) {
        try {
            payments.finishRefund(paymentId);
        } catch (RuntimeException e) {
            log.warn(
                    "Refund of payment {} not confirmed by the provider yet; it stays REFUNDING: {}",
                    paymentId,
                    e.toString());
        }
    }
}
