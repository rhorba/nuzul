package ma.nuzul.reservation.application;

import java.time.Clock;
import java.time.Instant;
import ma.nuzul.reservation.ReservationEvent;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import ma.nuzul.shared.outbox.Aggregate;
import ma.nuzul.shared.outbox.OutboxPort;

/**
 * Builds the {@link ReservationEvent} for a status change and appends it to the outbox. Called by the transaction
 * classes only after their conditional write succeeded, so the event commits or rolls back with the change it
 * describes. Kept here rather than on the public record so the module's API does not expose the domain type.
 */
final class ReservationEvents {

    private ReservationEvents() {}

    /** @param status the status {@code reservation} has just moved to (its {@code status()} may be the old one) */
    static void append(OutboxPort outbox, Clock clock, Reservation reservation, ReservationStatus status, String type) {
        outbox.append(
                Aggregate.RESERVATION,
                reservation.id(),
                type,
                new ReservationEvent(
                        reservation.id(),
                        reservation.hotelId(),
                        reservation.roomTypeId(),
                        reservation.checkIn(),
                        reservation.checkOut(),
                        reservation.rooms(),
                        status.name(),
                        reservation.totalMad().toPlainString(),
                        Instant.now(clock)));
    }
}
