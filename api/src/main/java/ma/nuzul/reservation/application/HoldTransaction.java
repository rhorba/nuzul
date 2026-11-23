package ma.nuzul.reservation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import ma.nuzul.inventory.InventoryReservations;
import ma.nuzul.inventory.StayPricing;
import ma.nuzul.inventory.StayQuote;
import ma.nuzul.reservation.domain.CancellationPolicy;
import ma.nuzul.reservation.domain.Guest;
import ma.nuzul.reservation.domain.HoldRequest;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import ma.nuzul.shared.error.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One hold, one short transaction, no external calls (system design: payment stays outside). Order matters:
 *
 * <ol>
 *   <li>take the guest's advisory lock (transaction-scoped): this guest's holds run one at a time, so the count below
 *       cannot be raced by the guest's own parallel requests. Taken before any inventory row lock, and no other
 *       transaction takes it after one, so it adds no deadlock path;
 *   <li>replay a reservation this guest already committed with the key: a replay is never refused by the cap;
 *   <li>count the guest's active holds (HELD and not expired at the same instant the new hold starts from) and refuse
 *       a new one at {@code app.rate-limit.max-active-holds} (429 TOO_MANY_ACTIVE_HOLDS) before anything is written;
 *   <li>quote the stay from the rate rows: price and cancellation policy, both frozen on the reservation (a night
 *       without a rate is SOLD_OUT);
 *   <li>insert the reservation, keyed on (guest, idempotency key). Under the guest lock a duplicate key is already
 *       caught in step 2; the unique index stays as the safety net: a conflicting insert replays the committed row;
 *   <li>reserve the rooms with the guarded UPDATE (inventory locks taken in ascending night order).
 * </ol>
 *
 * SOLD_OUT from reserve() rolls the whole transaction back, the reservation row included.
 */
@Component
class HoldTransaction {

    static final String TOO_MANY_ACTIVE_HOLDS = "TOO_MANY_ACTIVE_HOLDS";

    private final ReservationRepositoryPort reservations;
    private final StayPricing pricing;
    private final InventoryReservations inventory;
    private final Clock clock;
    private final Duration holdDuration;
    private final int maxActiveHolds;

    HoldTransaction(
            ReservationRepositoryPort reservations,
            StayPricing pricing,
            InventoryReservations inventory,
            Clock clock,
            @Value("${app.hold-minutes}") long holdMinutes,
            @Value("${app.rate-limit.max-active-holds}") int maxActiveHolds) {
        this.reservations = reservations;
        this.pricing = pricing;
        this.inventory = inventory;
        this.clock = clock;
        this.holdDuration = Duration.ofMinutes(holdMinutes);
        this.maxActiveHolds = maxActiveHolds;
    }

    /** @return the reservation this call created, or the one an earlier call with the same key committed */
    @Transactional
    Reservation hold(Guest guest, UUID idempotencyKey, HoldRequest request, UUID hotelId) {
        reservations.lockGuestHolds(guest.sub());
        var known = reservations.findByIdempotencyKey(guest.sub(), idempotencyKey);
        if (known.isPresent()) {
            return known.get();
        }
        Instant now = Instant.now(clock);
        requireBelowCap(guest.sub(), now);

        StayQuote quote = pricing.quote(request.roomTypeId(), request.checkIn(), request.checkOut(), request.rooms());
        Instant expiresAt = now.plus(holdDuration).truncatedTo(ChronoUnit.MICROS);
        Reservation reservation = new Reservation(
                UUID.randomUUID(),
                guest.sub(),
                hotelId,
                request.roomTypeId(),
                request.checkIn(),
                request.checkOut(),
                request.rooms(),
                ReservationStatus.HELD,
                quote.totalMad(),
                expiresAt,
                idempotencyKey,
                guest.name(),
                guest.email(),
                CancellationPolicy.forStay(
                        quote.refundable(), quote.freeCancelHours(), request.checkIn(), clock.getZone()));

        if (!reservations.insertIfKeyAbsent(reservation)) {
            // Safety net (step 5). READ COMMITTED: this new statement sees the row the other transaction committed.
            return reservations
                    .findByIdempotencyKey(guest.sub(), idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("idempotency key row not visible after conflict"));
        }
        inventory.reserve(request.roomTypeId(), request.checkIn(), request.checkOut(), request.rooms());
        return reservation;
    }

    /** No Retry-After: the cap lifts when the guest pays, cancels or lets a hold expire, not after a set time. */
    private void requireBelowCap(String guestSub, Instant now) {
        if (reservations.countActiveHolds(guestSub, now) >= maxActiveHolds) {
            throw new TooManyRequestsException(
                    TOO_MANY_ACTIVE_HOLDS,
                    "You already have " + maxActiveHolds
                            + " unpaid holds. Pay for or cancel one of them before holding another room.");
        }
    }
}
