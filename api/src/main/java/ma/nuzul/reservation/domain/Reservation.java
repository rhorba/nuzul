package ma.nuzul.reservation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A reservation row. {@code totalMad} is frozen at hold time from the rate rows (database doc §3 rule 3);
 * {@code holdExpiresAt} is truncated to microseconds, the precision Postgres stores, so a value read back equals the
 * one first returned.
 */
public record Reservation(
        UUID id,
        String guestSub,
        UUID hotelId,
        UUID roomTypeId,
        LocalDate checkIn,
        LocalDate checkOut,
        int rooms,
        ReservationStatus status,
        BigDecimal totalMad,
        Instant holdExpiresAt,
        UUID idempotencyKey,
        String guestName,
        String guestEmail) {

    /** Was this reservation created for exactly this request? (The idempotency key must not be reused.) */
    public boolean isFor(HoldRequest request) {
        return roomTypeId.equals(request.roomTypeId())
                && checkIn.equals(request.checkIn())
                && checkOut.equals(request.checkOut())
                && rooms == request.rooms();
    }
}
