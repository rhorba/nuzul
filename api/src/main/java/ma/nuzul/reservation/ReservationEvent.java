package ma.nuzul.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The payload of every reservation event on {@code reservation-events}, published by the outbox in the transaction
 * that changed the status. Public at the module's top level so other modules (search, notification) may consume it.
 *
 * <p>It carries what a consumer needs to react without reading the reservation table, and nothing personal: no guest
 * sub, name or email, no idempotency key, no payment data (PII stays out of Kafka). {@code totalMad} is a plain decimal
 * string, never a JSON float, so money keeps its exact value. {@code status} is the status the change moved to.
 */
public record ReservationEvent(
        UUID reservationId,
        UUID hotelId,
        UUID roomTypeId,
        LocalDate checkIn,
        LocalDate checkOut,
        int rooms,
        String status,
        String totalMad,
        Instant occurredAt) {

    /** Event type names: the outbox {@code type} column and the Kafka {@code event-type} header. */
    public static final String HELD = "ReservationHeld";

    public static final String CONFIRMED = "ReservationConfirmed";
    public static final String CANCELLED = "ReservationCancelled";
    public static final String EXPIRED = "ReservationExpired";
}
