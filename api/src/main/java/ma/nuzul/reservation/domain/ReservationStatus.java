package ma.nuzul.reservation.domain;

/** Matches the reservation.status CHECK constraint. A hold starts HELD; 2.2-2.4 move it on. */
public enum ReservationStatus {
    HELD,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
