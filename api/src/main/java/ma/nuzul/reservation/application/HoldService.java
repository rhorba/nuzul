package ma.nuzul.reservation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.catalog.BookableRoomTypes;
import ma.nuzul.inventory.domain.BookingWindow;
import ma.nuzul.reservation.domain.Guest;
import ma.nuzul.reservation.domain.HoldRequest;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.shared.error.DomainValidationException;
import ma.nuzul.shared.error.NotFoundException;
import ma.nuzul.shared.error.UnprocessableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Replays a known key first (Redis, then Postgres), so a retried request gets its first answer even after the stay
 * has left the booking window. Otherwise validates everything it can before any write transaction opens (a
 * validation error thrown inside reserve() would mark the transaction rollback-only), then runs
 * {@link HoldTransaction} and caches the committed answer.
 *
 * <p>A deadlock or serialization failure (40P01/40001, PessimisticLockingFailureException) replays the WHOLE hold
 * transaction, a few times with jittered backoff; reserve() itself is never retried (its transaction is already
 * rolled back). Retrying is safe: the idempotency key makes a second hold for the same key impossible.
 */
@Service
class HoldService implements HoldReservationUseCase {

    static final String ROOM_TYPE_NOT_FOUND = "ROOM_TYPE_NOT_FOUND";
    static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    static final int MAX_RETRIES = 3;

    private final HoldTransaction holdTransaction;
    private final ReservationRepositoryPort reservations;
    private final BookableRoomTypes bookable;
    private final IdempotencyCache cache;
    private final Clock clock;
    private final RetryTemplate retry;

    @Autowired
    HoldService(
            HoldTransaction holdTransaction,
            ReservationRepositoryPort reservations,
            BookableRoomTypes bookable,
            IdempotencyCache cache,
            Clock clock) {
        this(holdTransaction, reservations, bookable, cache, clock, Duration.ofMillis(20));
    }

    HoldService(
            HoldTransaction holdTransaction,
            ReservationRepositoryPort reservations,
            BookableRoomTypes bookable,
            IdempotencyCache cache,
            Clock clock,
            Duration retryDelay) {
        this.holdTransaction = holdTransaction;
        this.reservations = reservations;
        this.bookable = bookable;
        this.cache = cache;
        this.clock = clock;
        this.retry = new RetryTemplate(RetryPolicy.builder()
                .includes(PessimisticLockingFailureException.class)
                .maxRetries(MAX_RETRIES)
                .delay(retryDelay)
                .jitter(retryDelay.dividedBy(2))
                .multiplier(2)
                .build());
    }

    @Override
    public HeldReservation hold(Guest guest, UUID idempotencyKey, HoldRequest request) {
        if (guest == null || idempotencyKey == null || request == null) {
            throw new DomainValidationException("guest, idempotency key and request are required");
        }
        Optional<IdempotencyCache.Entry> cached = cache.get(guest.sub(), idempotencyKey);
        if (cached.isPresent()) {
            requireSameRequest(cached.get().request().equals(request));
            return cached.get().response();
        }
        Optional<Reservation> known = reservations.findByIdempotencyKey(guest.sub(), idempotencyKey);
        if (known.isPresent()) {
            return answer(guest, idempotencyKey, request, known.get());
        }

        new BookingWindow(LocalDate.now(clock))
                .requireContains(List.of(request.checkIn(), request.checkOut().minusDays(1)));
        UUID hotelId = bookable.hotelOf(request.roomTypeId())
                .orElseThrow(() ->
                        new NotFoundException(ROOM_TYPE_NOT_FOUND, "Room type " + request.roomTypeId() + " not found"));

        Reservation held = retry.invoke(() -> holdTransaction.hold(guest, idempotencyKey, request, hotelId));
        return answer(guest, idempotencyKey, request, held);
    }

    /** The response for a committed reservation of this key; cached so the next replay skips Postgres. */
    private HeldReservation answer(Guest guest, UUID idempotencyKey, HoldRequest request, Reservation reservation) {
        requireSameRequest(reservation.isFor(request));
        HeldReservation response = HeldReservation.of(reservation);
        cache.put(guest.sub(), idempotencyKey, new IdempotencyCache.Entry(request, response));
        return response;
    }

    private static void requireSameRequest(boolean same) {
        if (!same) {
            throw new UnprocessableException(
                    IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different reservation request",
                    Map.of());
        }
    }
}
