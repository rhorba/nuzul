package ma.nuzul.reservation.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;

/**
 * The response to a hold, identical for the first call and every replay of its idempotency key. {@code status} is
 * the status the hold was created with (HELD), not the reservation's current one.
 */
public record HeldReservation(UUID id, ReservationStatus status, Instant holdExpiresAt, BigDecimal totalMad) {

    static HeldReservation of(Reservation r) {
        return new HeldReservation(r.id(), ReservationStatus.HELD, r.holdExpiresAt(), r.totalMad());
    }
}
