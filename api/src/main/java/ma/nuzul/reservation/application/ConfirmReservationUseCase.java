package ma.nuzul.reservation.application;

import java.math.BigDecimal;
import java.util.UUID;
import ma.nuzul.reservation.domain.ReservationStatus;

/**
 * In-port: a guest pays for their hold, which becomes CONFIRMED. Retry-safe: confirming an already confirmed
 * reservation answers 200 again, and concurrent confirms charge at most once.
 */
public interface ConfirmReservationUseCase {

    record Confirmed(UUID id, ReservationStatus status, BigDecimal totalMad) {}

    /**
     * @throws ma.nuzul.shared.error.NotFoundException RESERVATION_NOT_FOUND (404), also for another guest's reservation
     * @throws ma.nuzul.shared.error.GoneException HOLD_EXPIRED (410), refunded if it expired while being paid
     * @throws ma.nuzul.shared.error.PaymentRequiredException PAYMENT_FAILED (402): the hold stays HELD
     * @throws ma.nuzul.shared.error.ConflictException RESERVATION_CANCELLED (409)
     */
    Confirmed confirm(String guestSub, UUID reservationId, String paymentToken);
}
