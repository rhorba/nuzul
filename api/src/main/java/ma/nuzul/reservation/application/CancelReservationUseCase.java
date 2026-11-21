package ma.nuzul.reservation.application;

import java.math.BigDecimal;
import java.util.UUID;
import ma.nuzul.reservation.domain.ReservationStatus;

/**
 * In-port: a guest cancels a HELD or CONFIRMED reservation. The rooms go back on sale in the same transaction as the
 * status change; a CONFIRMED stay cancelled while its frozen policy is free is refunded in full. Retry-safe: cancelling
 * again answers with the same outcome (and finishes a refund the provider had not confirmed yet).
 */
public interface CancelReservationUseCase {

    /** NONE: nothing to give back. PENDING: claimed, the provider has not confirmed yet. COMPLETED: money returned. */
    enum RefundStatus {
        NONE,
        PENDING,
        COMPLETED
    }

    record Cancelled(UUID id, ReservationStatus status, RefundStatus refund, BigDecimal refundMad) {}

    /**
     * @throws ma.nuzul.shared.error.NotFoundException RESERVATION_NOT_FOUND (404), also for another guest's reservation
     * @throws ma.nuzul.shared.error.GoneException HOLD_EXPIRED (410) for an expired hold
     */
    Cancelled cancel(String guestSub, UUID reservationId);
}
