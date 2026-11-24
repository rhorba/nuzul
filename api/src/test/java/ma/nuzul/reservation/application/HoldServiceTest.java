package ma.nuzul.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import ma.nuzul.catalog.BookableRoomTypes;
import ma.nuzul.inventory.InventoryReservations;
import ma.nuzul.inventory.SoldOutException;
import ma.nuzul.inventory.StayPricing;
import ma.nuzul.inventory.StayQuote;
import ma.nuzul.inventory.domain.BookingWindow;
import ma.nuzul.reservation.ReservationEvent;
import ma.nuzul.reservation.domain.Guest;
import ma.nuzul.reservation.domain.HoldRequest;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import ma.nuzul.shared.error.CodedException;
import ma.nuzul.shared.error.DomainValidationException;
import ma.nuzul.shared.error.NotFoundException;
import ma.nuzul.shared.error.TooManyRequestsException;
import ma.nuzul.shared.error.UnprocessableException;
import ma.nuzul.shared.outbox.Aggregate;
import ma.nuzul.shared.outbox.RecordingOutbox;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

/** Hold rules against in-memory ports and a fixed clock; no Spring, so no transactions (the ITs prove those). */
class HoldServiceTest {

    /* A fixed offset, not Africa/Casablanca: Morocco's offset differs between JDK tzdata releases. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-20T10:15:30.123456789Z"), ZoneOffset.ofHours(1));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Guest GUEST = new Guest("guest-1", "Amina Guest", "amina@example.ma");
    private static final int MAX_HOLDS = 5;

    private final UUID roomTypeId = UUID.randomUUID();
    private final UUID hotelId = UUID.randomUUID();
    private final InMemoryReservations reservations = new InMemoryReservations();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final MapCache cache = new MapCache();
    private final List<String> reserveCalls = new ArrayList<>();
    /** Port calls made inside the hold transaction, in order. */
    private final List<String> calls = new ArrayList<>();

    private final AtomicInteger lockFailuresLeft = new AtomicInteger();
    private boolean soldOut;

    private final StayPricing pricing = (id, in, out, rooms) -> new StayQuote(
            new BigDecimal("650.50")
                    .multiply(BigDecimal.valueOf(in.until(out).getDays()))
                    .multiply(BigDecimal.valueOf(rooms)),
            true,
            48);
    private final InventoryReservations inventory = reserving((id, in, out, rooms) -> {
        if (lockFailuresLeft.getAndDecrement() > 0) {
            throw new CannotAcquireLockException("deadlock detected (40P01)");
        }
        if (soldOut) {
            throw new SoldOutException();
        }
        reserveCalls.add(id + " " + in + " " + out + " " + rooms);
        calls.add("reserve");
    });
    private final BookableRoomTypes bookable = id -> id.equals(roomTypeId) ? Optional.of(hotelId) : Optional.empty();

    private final HoldService service = new HoldService(
            new HoldTransaction(reservations, pricing, inventory, outbox, CLOCK, 10, MAX_HOLDS),
            reservations,
            bookable,
            cache,
            CLOCK,
            Duration.ofMillis(1));

    /** The hold only reserves; release belongs to cancel and expiry. */
    private interface Reserve {
        void reserve(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms);
    }

    private static InventoryReservations reserving(Reserve reserve) {
        return new InventoryReservations() {
            @Override
            public void reserve(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
                reserve.reserve(roomTypeId, checkIn, checkOut, rooms);
            }

            @Override
            public void release(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
                throw new UnsupportedOperationException("not used by hold");
            }
        };
    }

    private HoldRequest request(int fromDays, int nights, int rooms) {
        return new HoldRequest(roomTypeId, TODAY.plusDays(fromDays), TODAY.plusDays(fromDays + nights), rooms);
    }

    private static CodedException thrown(Runnable call) {
        Throwable t = catchThrowable(call::run);
        assertThat(t).isInstanceOf(CodedException.class);
        return (CodedException) t;
    }

    @Test
    void holdsPricesAndExpiresInTenMinutes() {
        UUID key = UUID.randomUUID();

        HeldReservation held = service.hold(GUEST, key, request(5, 3, 2));

        assertThat(held.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(held.totalMad()).isEqualTo(new BigDecimal("3903.00"));
        assertThat(held.holdExpiresAt()).isEqualTo(Instant.parse("2026-11-20T10:25:30.123456Z"));
        assertThat(reserveCalls).containsExactly(roomTypeId + " " + TODAY.plusDays(5) + " " + TODAY.plusDays(8) + " 2");

        Reservation row = reservations.byKey.get(GUEST.sub() + "|" + key);
        assertThat(row.hotelId()).isEqualTo(hotelId);
        assertThat(row.guestName()).isEqualTo("Amina Guest");
        assertThat(row.guestEmail()).isEqualTo("amina@example.ma");
        assertThat(row.status()).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void aNewHoldAppendsOneReservationHeldEventWithoutPersonalData() {
        HeldReservation held = service.hold(GUEST, UUID.randomUUID(), request(5, 3, 2));

        assertThat(outbox.events).hasSize(1);
        RecordingOutbox.Event e = outbox.events.get(0);
        assertThat(e.aggregate()).isEqualTo(Aggregate.RESERVATION);
        assertThat(e.aggregateId()).isEqualTo(held.id());
        assertThat(e.type()).isEqualTo(ReservationEvent.HELD);
        assertThat(e.payload())
                .isEqualTo(new ReservationEvent(
                        held.id(),
                        hotelId,
                        roomTypeId,
                        TODAY.plusDays(5),
                        TODAY.plusDays(8),
                        2,
                        "HELD",
                        "3903.00",
                        Instant.now(CLOCK)));
    }

    @Test
    void aKnownKeyReplayAppendsNoSecondEvent() {
        UUID key = UUID.randomUUID();
        service.hold(GUEST, key, request(5, 3, 2));
        cache.entries.clear();
        reservations.hiddenLookups = 1; // the pre-check misses it: the replay is found under the guest lock

        service.hold(GUEST, key, request(5, 3, 2));

        assertThat(outbox.types()).containsExactly(ReservationEvent.HELD);
    }

    @Test
    void anInsertConflictReplayAppendsNoSecondEvent() {
        UUID key = UUID.randomUUID();
        service.hold(GUEST, key, request(5, 3, 2));
        cache.entries.clear();
        reservations.hiddenLookups = 2; // both look-ups miss it: the insert conflicts (the safety net)

        service.hold(GUEST, key, request(5, 3, 2));

        assertThat(outbox.types()).containsExactly(ReservationEvent.HELD);
    }

    @Test
    void soldOutAppendsNoEvent() {
        soldOut = true;

        catchThrowable(() -> service.hold(GUEST, UUID.randomUUID(), request(5, 3, 2)));

        assertThat(outbox.events).isEmpty();
    }

    @Test
    void aRepeatedKeyReplaysTheSameResponseWithoutHoldingAgain() {
        UUID key = UUID.randomUUID();
        HeldReservation first = service.hold(GUEST, key, request(5, 3, 2));

        HeldReservation again = service.hold(GUEST, key, request(5, 3, 2));

        assertThat(again).isEqualTo(first);
        assertThat(reserveCalls).hasSize(1);
        assertThat(reservations.byKey).hasSize(1);
    }

    @Test
    void aKeyLostToAConcurrentInsertReplaysTheCommittedRow() {
        UUID key = UUID.randomUUID();
        HeldReservation first = service.hold(GUEST, key, request(5, 3, 2));
        reservations.hiddenLookups = 2; // both look-ups miss it, the insert then conflicts (the safety net)

        assertThat(service.hold(GUEST, key, request(5, 3, 2))).isEqualTo(first);
        assertThat(reserveCalls).hasSize(1);
    }

    @Test
    void theSameKeyForAnotherRequestIsUnprocessable() {
        UUID key = UUID.randomUUID();
        service.hold(GUEST, key, request(5, 3, 2));

        for (HoldRequest other : List.of(request(5, 3, 1), request(6, 3, 2), request(5, 4, 2))) {
            var e = thrown(() -> service.hold(GUEST, key, other));
            assertThat(e).isInstanceOf(UnprocessableException.class);
            assertThat(e.code()).isEqualTo(HoldService.IDEMPOTENCY_KEY_REUSED);
        }
        var otherRoomType = new HoldRequest(UUID.randomUUID(), TODAY.plusDays(5), TODAY.plusDays(8), 2);
        assertThat(thrown(() -> service.hold(GUEST, key, otherRoomType)).code())
                .isEqualTo(HoldService.IDEMPOTENCY_KEY_REUSED);
        assertThat(reserveCalls).hasSize(1);
    }

    @Test
    void keysArePerGuest() {
        UUID key = UUID.randomUUID();
        service.hold(GUEST, key, request(5, 3, 2));

        service.hold(new Guest("guest-2", "Youssef", "y@example.ma"), key, request(5, 3, 2));

        assertThat(reserveCalls).hasSize(2);
    }

    @Test
    void soldOutLeavesNoReservation() {
        soldOut = true;

        assertThat(catchThrowable(() -> service.hold(GUEST, UUID.randomUUID(), request(5, 3, 2))))
                .isInstanceOf(SoldOutException.class);
        // One attempt only: SOLD_OUT is never retried (the IT proves the reservation row rolls back).
        assertThat(lockFailuresLeft.get()).isEqualTo(-1);
    }

    @Test
    void aDeadlockReplaysTheWholeHoldTransaction() {
        lockFailuresLeft.set(HoldService.MAX_RETRIES);
        reservations.rollBackOnLockFailure = true;

        HeldReservation held = service.hold(GUEST, UUID.randomUUID(), request(5, 1, 1));

        assertThat(held.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(reserveCalls).hasSize(1);
    }

    @Test
    void retriesAreBounded() {
        lockFailuresLeft.set(HoldService.MAX_RETRIES + 1);
        reservations.rollBackOnLockFailure = true;

        assertThat(catchThrowable(() -> service.hold(GUEST, UUID.randomUUID(), request(5, 1, 1))))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(reserveCalls).isEmpty();
    }

    @Test
    void otherDatabaseErrorsAreNotRetried() {
        HoldService failing = new HoldService(
                new HoldTransaction(
                        reservations,
                        pricing,
                        reserving((id, in, out, rooms) -> {
                            reserveCalls.add("attempt");
                            throw new DataIntegrityViolationException("check violation");
                        }),
                        outbox,
                        CLOCK,
                        10,
                        MAX_HOLDS),
                reservations,
                bookable,
                cache,
                CLOCK,
                Duration.ofMillis(1));

        assertThat(catchThrowable(() -> failing.hold(GUEST, UUID.randomUUID(), request(5, 1, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reserveCalls).hasSize(1);
    }

    @Test
    void unknownOrInactiveRoomTypeIsNotFound() {
        var request = new HoldRequest(UUID.randomUUID(), TODAY.plusDays(1), TODAY.plusDays(2), 1);

        var e = thrown(() -> service.hold(GUEST, UUID.randomUUID(), request));

        assertThat(e).isInstanceOf(NotFoundException.class);
        assertThat(e.code()).isEqualTo(HoldService.ROOM_TYPE_NOT_FOUND);
    }

    @Test
    void staysOutsideTheBookingWindowAreRefusedBeforeAnyWrite() {
        var past = new HoldRequest(roomTypeId, TODAY.minusDays(1), TODAY.plusDays(1), 1);
        var tooFar = request(BookingWindow.DAYS - 1, 2, 1);

        assertThat(thrown(() -> service.hold(GUEST, UUID.randomUUID(), past)).code())
                .isEqualTo(BookingWindow.NIGHT_OUTSIDE_WINDOW);
        assertThat(thrown(() -> service.hold(GUEST, UUID.randomUUID(), tooFar)).code())
                .isEqualTo(BookingWindow.NIGHT_OUTSIDE_WINDOW);
        assertThat(service.hold(GUEST, UUID.randomUUID(), request(BookingWindow.DAYS - 1, 1, 1))
                        .status())
                .isEqualTo(ReservationStatus.HELD);
        assertThat(service.hold(GUEST, UUID.randomUUID(), request(0, 1, 1)).status())
                .isEqualTo(ReservationStatus.HELD);
        assertThat(reserveCalls).hasSize(2);
    }

    @Test
    void missingArgumentsAreRefused() {
        assertThat(catchThrowable(() -> service.hold(null, UUID.randomUUID(), request(1, 1, 1))))
                .isInstanceOf(DomainValidationException.class);
        assertThat(catchThrowable(() -> service.hold(GUEST, null, request(1, 1, 1))))
                .isInstanceOf(DomainValidationException.class);
        assertThat(catchThrowable(() -> service.hold(GUEST, UUID.randomUUID(), null)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void aCachedKeyIsReplayedWithoutTouchingPostgres() {
        UUID key = UUID.randomUUID();
        HeldReservation first = service.hold(GUEST, key, request(5, 3, 2));
        reservations.byKey.clear(); // proves the replay came from the cache

        assertThat(service.hold(GUEST, key, request(5, 3, 2))).isEqualTo(first);
        assertThat(reserveCalls).hasSize(1);
    }

    @Test
    void aCachedKeyForAnotherRequestIsUnprocessable() {
        UUID key = UUID.randomUUID();
        service.hold(GUEST, key, request(5, 3, 2));

        assertThat(thrown(() -> service.hold(GUEST, key, request(5, 3, 1))).code())
                .isEqualTo(HoldService.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void aPostgresReplayRefillsTheCache() {
        UUID key = UUID.randomUUID();
        HeldReservation first = service.hold(GUEST, key, request(5, 3, 2));
        cache.entries.clear(); // e.g. Redis flushed or was down

        assertThat(service.hold(GUEST, key, request(5, 3, 2))).isEqualTo(first);
        assertThat(cache.entries).containsKey(GUEST.sub() + "|" + key);
    }

    @Test
    void aKnownKeyIsReplayedEvenOnceItsStayHasLeftTheWindow() {
        UUID key = UUID.randomUUID();
        HeldReservation first = service.hold(GUEST, key, request(0, 1, 1));
        cache.entries.clear();
        HoldService tomorrow = new HoldService(
                new HoldTransaction(reservations, pricing, inventory, outbox, CLOCK, 10, MAX_HOLDS),
                reservations,
                bookable,
                cache,
                Clock.offset(CLOCK, Duration.ofDays(2)),
                Duration.ofMillis(1));

        assertThat(tomorrow.hold(GUEST, key, request(0, 1, 1))).isEqualTo(first);
    }

    /** {@code count} HELD reservations of {@code guest}, all still active (nothing reserved for them here). */
    private void activeHolds(Guest guest, int count) {
        for (int i = 0; i < count; i++) {
            service.hold(guest, UUID.randomUUID(), request(5, 1, 1));
        }
        reserveCalls.clear();
        calls.clear();
    }

    @Test
    void theGuestLockIsTakenFirstThenAKnownKeyIsLookedUpThenActiveHoldsAreCountedThenTheHoldIsWritten() {
        service.hold(GUEST, UUID.randomUUID(), request(5, 1, 1));

        assertThat(calls)
                .containsExactly(
                        "find guest-1", // HoldService's replay pre-check, outside the transaction
                        "lock guest-1",
                        "find guest-1",
                        "count guest-1 at " + Instant.now(CLOCK),
                        "insert guest-1",
                        "reserve");
    }

    @Test
    void theCapRefusesTheNextHoldBeforeAnyWrite() {
        activeHolds(GUEST, MAX_HOLDS);

        Throwable t = catchThrowable(() -> service.hold(GUEST, UUID.randomUUID(), request(5, 1, 1)));

        assertThat(t).isInstanceOf(TooManyRequestsException.class);
        var e = (TooManyRequestsException) t;
        assertThat(e.code()).isEqualTo(HoldTransaction.TOO_MANY_ACTIVE_HOLDS);
        assertThat(e.retryAfterSeconds()).isEmpty();
        assertThat(e.getMessage()).contains("5");
        assertThat(calls)
                .containsExactly(
                        "find guest-1", "lock guest-1", "find guest-1", "count guest-1 at " + Instant.now(CLOCK));
        assertThat(reserveCalls).isEmpty();
        assertThat(reservations.byKey).hasSize(MAX_HOLDS);
    }

    @Test
    void oneBelowTheCapStillHolds() {
        activeHolds(GUEST, MAX_HOLDS - 1);

        assertThat(service.hold(GUEST, UUID.randomUUID(), request(5, 1, 1)).status())
                .isEqualTo(ReservationStatus.HELD);
        assertThat(reserveCalls).hasSize(1);
    }

    @Test
    void theCapIsPerGuest() {
        activeHolds(GUEST, MAX_HOLDS);

        assertThat(service.hold(new Guest("guest-2", "Youssef", "y@example.ma"), UUID.randomUUID(), request(5, 1, 1))
                        .status())
                .isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void aReplayFoundUnderTheGuestLockIsNeverRefusedByTheCap() {
        UUID key = UUID.randomUUID();
        HeldReservation first = service.hold(GUEST, key, request(5, 3, 2));
        activeHolds(GUEST, MAX_HOLDS - 1);
        cache.entries.clear();
        reservations.hiddenLookups = 1; // the pre-check misses it (a concurrent duplicate), the lock holder sees it

        assertThat(service.hold(GUEST, key, request(5, 3, 2))).isEqualTo(first);
        assertThat(calls).containsExactly("find guest-1", "lock guest-1", "find guest-1");
        assertThat(reserveCalls).isEmpty();
    }

    /** Stands in for Redis. */
    private static final class MapCache implements IdempotencyCache {

        final Map<String, Entry> entries = new HashMap<>();

        @Override
        public Optional<Entry> get(String guestSub, UUID idempotencyKey) {
            return Optional.ofNullable(entries.get(guestSub + "|" + idempotencyKey));
        }

        @Override
        public void put(String guestSub, UUID idempotencyKey, Entry entry) {
            entries.put(guestSub + "|" + idempotencyKey, entry);
        }
    }

    /** Mirrors the SQL: insert unless (guest, key) exists. Optionally hides a row from the next look-ups, or undoes a failed attempt. */
    private final class InMemoryReservations implements ReservationRepositoryPort {

        final Map<String, Reservation> byKey = new HashMap<>();
        int hiddenLookups;
        boolean rollBackOnLockFailure;

        @Override
        public boolean insertIfKeyAbsent(Reservation r) {
            calls.add("insert " + r.guestSub());
            String key = r.guestSub() + "|" + r.idempotencyKey();
            if (byKey.containsKey(key)) {
                return false;
            }
            if (rollBackOnLockFailure && lockFailuresLeft.get() > 0) {
                return true; // the attempt fails in reserve() and its transaction would roll this insert back
            }
            byKey.put(key, r);
            return true;
        }

        @Override
        public Optional<Reservation> findByIdempotencyKey(String guestSub, UUID idempotencyKey) {
            calls.add("find " + guestSub);
            if (hiddenLookups > 0) {
                hiddenLookups--;
                return Optional.empty();
            }
            return Optional.ofNullable(byKey.get(guestSub + "|" + idempotencyKey));
        }

        @Override
        public void lockGuestHolds(String guestSub) {
            calls.add("lock " + guestSub);
        }

        /** Mirrors the SQL: HELD and not yet expired at {@code now}. */
        @Override
        public int countActiveHolds(String guestSub, Instant now) {
            calls.add("count " + guestSub + " at " + now);
            return (int) byKey.values().stream()
                    .filter(r -> r.guestSub().equals(guestSub))
                    .filter(r -> r.status() == ReservationStatus.HELD)
                    .filter(r -> r.holdExpiresAt().isAfter(now))
                    .count();
        }

        @Override
        public Optional<Reservation> findForGuest(UUID id, String guestSub) {
            throw new UnsupportedOperationException("not used by hold");
        }

        @Override
        public Optional<ReservationStatus> findStatus(UUID id) {
            throw new UnsupportedOperationException("not used by hold");
        }

        @Override
        public boolean confirmIfHeld(UUID id, Instant now) {
            throw new UnsupportedOperationException("not used by hold");
        }

        @Override
        public boolean cancelIf(UUID id, ReservationStatus from) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public java.util.List<UUID> expiredHoldIds(java.time.Instant now, int limit) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public java.util.Optional<Reservation> lockExpiredHold(UUID id, java.time.Instant now) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public boolean expireIfHeld(UUID id) {
            throw new UnsupportedOperationException("not used here");
        }
    }
}
