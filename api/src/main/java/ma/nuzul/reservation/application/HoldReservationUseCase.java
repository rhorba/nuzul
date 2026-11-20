package ma.nuzul.reservation.application;

import java.util.UUID;
import ma.nuzul.reservation.domain.Guest;
import ma.nuzul.reservation.domain.HoldRequest;

/**
 * In-port: a guest holds rooms for a stay. Idempotent per (guest, key): a repeat, sequential or concurrent, returns
 * the first call's result and never holds twice.
 */
public interface HoldReservationUseCase {

    /**
     * @throws ma.nuzul.inventory.SoldOutException SOLD_OUT (409) when a night is full, unstocked or unpriced
     * @throws ma.nuzul.shared.error.NotFoundException ROOM_TYPE_NOT_FOUND (404) for an unknown or inactive room type
     * @throws ma.nuzul.shared.error.UnprocessableException IDEMPOTENCY_KEY_REUSED (422) for a key already used for
     *     another request
     * @throws ma.nuzul.shared.error.DomainValidationException (400) for a stay outside the booking window
     */
    HeldReservation hold(Guest guest, UUID idempotencyKey, HoldRequest request);
}
