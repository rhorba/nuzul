package ma.nuzul.reservation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ma.nuzul.payment.ChargeResult;
import ma.nuzul.payment.PaymentStatus;
import ma.nuzul.payment.Payments;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import ma.nuzul.shared.error.ConflictException;
import ma.nuzul.shared.error.DomainValidationException;
import ma.nuzul.shared.error.GoneException;
import ma.nuzul.shared.error.NotFoundException;
import ma.nuzul.shared.error.PaymentRequiredException;
import org.springframework.stereotype.Service;

/**
 * Confirm without an external call inside any transaction (system design): start the payment (tx 1), charge it with
 * no transaction open, then confirm + capture (tx 2) only if the hold is still HELD and unexpired. If it expired while
 * being charged, the charge is refunded and the guest gets 410. Holds keep their rooms throughout, so confirm never
 * touches inventory.
 *
 * <p>Known gap, documented in the system design: a crash between the approved charge and tx 2 leaves the payment
 * PENDING. The next confirm resumes it (same provider idempotency key, no second charge); if the hold expires first, a
 * reconciliation job (future) must refund it.
 */
@Service
class ConfirmService implements ConfirmReservationUseCase {

    static final String RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND";
    static final String HOLD_EXPIRED = "HOLD_EXPIRED";
    static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    static final String RESERVATION_CANCELLED = "RESERVATION_CANCELLED";

    private final ReservationRepositoryPort reservations;
    private final ConfirmTransactions transactions;
    private final Payments payments;
    private final Clock clock;

    ConfirmService(
            ReservationRepositoryPort reservations, ConfirmTransactions transactions, Payments payments, Clock clock) {
        this.reservations = reservations;
        this.transactions = transactions;
        this.payments = payments;
        this.clock = clock;
    }

    @Override
    public Confirmed confirm(String guestSub, UUID reservationId, String paymentToken) {
        if (guestSub == null || reservationId == null || paymentToken == null || paymentToken.isBlank()) {
            throw new DomainValidationException("reservation id and payment token are required");
        }
        Reservation reservation = reservations
                .findForGuest(reservationId, guestSub)
                .orElseThrow(() ->
                        new NotFoundException(RESERVATION_NOT_FOUND, "Reservation " + reservationId + " not found"));
        switch (reservation.status()) {
            case CONFIRMED -> {
                return confirmed(reservation);
            }
            case CANCELLED ->
                throw new ConflictException(RESERVATION_CANCELLED, "This reservation was cancelled", Map.of());
            case EXPIRED -> throw expired();
            case HELD -> {
                if (!Instant.now(clock).isBefore(reservation.holdExpiresAt())) {
                    throw expired();
                }
            }
        }

        Payments.Active payment = transactions.start(reservation);
        if (payment.status() == PaymentStatus.CAPTURED) {
            return confirmed(reservation); // a concurrent confirm already paid (CAPTURED commits with CONFIRMED)
        }
        ChargeResult result = payments.charge(payment.paymentId(), paymentToken);
        if (!(result instanceof ChargeResult.Approved approved)) {
            throw new PaymentRequiredException(PAYMENT_FAILED, "The payment was declined; the hold is still yours");
        }
        if (finish(reservationId, payment, approved)) {
            return confirmed(reservation);
        }
        // Not confirmed by this call: refund, unless a concurrent confirm sharing this payment captured it first.
        if (!payments.refund(payment.paymentId(), approved.providerRef())) {
            return confirmed(reservation);
        }
        ReservationStatus current = reservations.findStatus(reservationId).orElse(ReservationStatus.EXPIRED);
        if (current == ReservationStatus.CANCELLED) {
            throw new ConflictException(RESERVATION_CANCELLED, "This reservation was cancelled", Map.of());
        }
        throw expired();
    }

    private boolean finish(UUID reservationId, Payments.Active payment, ChargeResult.Approved approved) {
        try {
            return transactions.finish(reservationId, payment.paymentId(), approved.providerRef());
        } catch (ConfirmTransactions.CaptureLost e) {
            return false; // rolled back: the refund that claimed the payment decides below
        }
    }

    private static Confirmed confirmed(Reservation reservation) {
        return new Confirmed(reservation.id(), ReservationStatus.CONFIRMED, reservation.totalMad());
    }

    private static GoneException expired() {
        return new GoneException(HOLD_EXPIRED, "The hold expired before it was paid");
    }
}
