package ma.nuzul.reservation.application;

import java.util.Optional;
import java.util.UUID;
import ma.nuzul.reservation.domain.HoldRequest;

/**
 * Out-port: a fast, disposable copy of each hold response, keyed by (guest, idempotency key), so a replay skips
 * Postgres. Never authoritative: the reservation_idem unique constraint is. Implementations fail open (a cache
 * outage reads as a miss and a failed write is dropped) and never throw.
 */
public interface IdempotencyCache {

    /** The response first given for this key, with the request it answered (to detect a reused key). */
    record Entry(HoldRequest request, HeldReservation response) {}

    Optional<Entry> get(String guestSub, UUID idempotencyKey);

    void put(String guestSub, UUID idempotencyKey, Entry entry);
}
