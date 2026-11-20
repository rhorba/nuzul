package ma.nuzul.reservation.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import ma.nuzul.inventory.InventoryReservations;
import ma.nuzul.inventory.StayPricing;
import ma.nuzul.reservation.domain.Guest;
import ma.nuzul.reservation.domain.HoldRequest;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One hold, one short transaction, no external calls (system design: payment stays outside). Order matters:
 *
 * <ol>
 *   <li>price the stay from the rate rows (a night without a rate is SOLD_OUT);
 *   <li>insert the reservation FIRST, keyed on (guest, idempotency key). A concurrent duplicate blocks here on the
 *       unique index until this transaction ends, so it never touches inventory: it replays the committed row, or
 *       goes ahead itself if this one rolled back;
 *   <li>reserve the rooms with the guarded UPDATE (inventory locks taken in ascending night order).
 * </ol>
 *
 * SOLD_OUT from reserve() rolls the whole transaction back, the reservation row included.
 */
@Component
class HoldTransaction {

    private final ReservationRepositoryPort reservations;
    private final StayPricing pricing;
    private final InventoryReservations inventory;
    private final Clock clock;
    private final Duration holdDuration;

    HoldTransaction(
            ReservationRepositoryPort reservations,
            StayPricing pricing,
            InventoryReservations inventory,
            Clock clock,
            @Value("${app.hold-minutes}") long holdMinutes) {
        this.reservations = reservations;
        this.pricing = pricing;
        this.inventory = inventory;
        this.clock = clock;
        this.holdDuration = Duration.ofMinutes(holdMinutes);
    }

    /** @return the reservation this call created, or the one an earlier call with the same key committed */
    @Transactional
    Reservation hold(Guest guest, UUID idempotencyKey, HoldRequest request, UUID hotelId) {
        BigDecimal total = pricing.price(request.roomTypeId(), request.checkIn(), request.checkOut(), request.rooms());
        Instant expiresAt = Instant.now(clock).plus(holdDuration).truncatedTo(ChronoUnit.MICROS);
        Reservation reservation = new Reservation(
                UUID.randomUUID(),
                guest.sub(),
                hotelId,
                request.roomTypeId(),
                request.checkIn(),
                request.checkOut(),
                request.rooms(),
                ReservationStatus.HELD,
                total,
                expiresAt,
                idempotencyKey,
                guest.name(),
                guest.email());

        if (!reservations.insertIfKeyAbsent(reservation)) {
            // READ COMMITTED: this new statement sees the row the other transaction just committed.
            return reservations
                    .findByIdempotencyKey(guest.sub(), idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("idempotency key row not visible after conflict"));
        }
        inventory.reserve(request.roomTypeId(), request.checkIn(), request.checkOut(), request.rooms());
        return reservation;
    }
}
