package ma.nuzul.reservation.application;

import java.util.Optional;
import java.util.UUID;
import ma.nuzul.reservation.domain.Reservation;

/** Out-port for reservation rows. */
public interface ReservationRepositoryPort {

    /**
     * Inserts the reservation unless the guest already has one with its idempotency key. When another transaction
     * is inserting that key right now, waits for it to finish (Postgres unique index): false once it committed, and
     * this insert goes ahead if it rolled back.
     *
     * @return true when inserted, false when the (guest, key) pair already exists
     */
    boolean insertIfKeyAbsent(Reservation reservation);

    Optional<Reservation> findByIdempotencyKey(String guestSub, UUID idempotencyKey);
}
