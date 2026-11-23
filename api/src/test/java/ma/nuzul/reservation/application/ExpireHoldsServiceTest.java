package ma.nuzul.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import ma.nuzul.inventory.InventoryReservations;
import ma.nuzul.reservation.domain.CancellationPolicy;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import org.junit.jupiter.api.Test;

/** Story 2.4: the expiry run over an in-memory repository and inventory (races and SQL are in HoldExpiryIT). */
class ExpireHoldsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final Map<UUID, Reservation> rows = new LinkedHashMap<>();
    /** Ids another transaction holds right now: lockExpiredHold skips them. */
    private final Set<UUID> lockedElsewhere = ConcurrentHashMap.newKeySet();
    /** Ids whose status changes between the lock and the update (cannot happen under a real row lock). */
    private final Set<UUID> changedAfterLock = ConcurrentHashMap.newKeySet();

    private final Set<UUID> releaseFails = ConcurrentHashMap.newKeySet();
    private final List<UUID> released = new ArrayList<>();

    private final ReservationRepositoryPort repository = new ReservationRepositoryPort() {
        @Override
        public boolean insertIfKeyAbsent(Reservation reservation) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public void lockGuestHolds(String guestSub) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public int countActiveHolds(String guestSub, java.time.Instant now) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public Optional<Reservation> findByIdempotencyKey(String guestSub, UUID idempotencyKey) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public Optional<Reservation> findForGuest(UUID id, String guestSub) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public Optional<ReservationStatus> findStatus(UUID id) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public boolean confirmIfHeld(UUID id, Instant now) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public boolean cancelIf(UUID id, ReservationStatus from) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public List<UUID> expiredHoldIds(Instant now, int limit) {
            return rows.values().stream()
                    .filter(r -> due(r, now))
                    .sorted(Comparator.comparing(Reservation::holdExpiresAt))
                    .limit(limit)
                    .map(Reservation::id)
                    .toList();
        }

        @Override
        public Optional<Reservation> lockExpiredHold(UUID id, Instant now) {
            Reservation r = rows.get(id);
            if (lockedElsewhere.contains(id) || r == null || !due(r, now)) {
                return Optional.empty();
            }
            if (changedAfterLock.remove(id)) {
                rows.put(id, withStatus(r, ReservationStatus.CONFIRMED));
            }
            return Optional.of(r);
        }

        @Override
        public boolean expireIfHeld(UUID id) {
            Reservation r = rows.get(id);
            if (r.status() != ReservationStatus.HELD) {
                return false;
            }
            rows.put(id, withStatus(r, ReservationStatus.EXPIRED));
            return true;
        }
    };

    private final InventoryReservations inventory = new InventoryReservations() {
        @Override
        public void reserve(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public void release(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
            if (releaseFails.contains(roomTypeId)) {
                throw new IllegalStateException("release refused");
            }
            released.add(roomTypeId);
        }
    };

    private ExpireHoldsService service(int batchSize) {
        return new ExpireHoldsService(repository, new ExpiryTransaction(repository, inventory), CLOCK, batchSize);
    }

    private static boolean due(Reservation r, Instant now) {
        return r.status() == ReservationStatus.HELD && !r.holdExpiresAt().isAfter(now);
    }

    private static Reservation withStatus(Reservation r, ReservationStatus status) {
        return new Reservation(
                r.id(),
                r.guestSub(),
                r.hotelId(),
                r.roomTypeId(),
                r.checkIn(),
                r.checkOut(),
                r.rooms(),
                status,
                r.totalMad(),
                r.holdExpiresAt(),
                r.idempotencyKey(),
                r.guestName(),
                r.guestEmail(),
                r.cancellationPolicy());
    }

    /** A reservation whose room type id doubles as its release marker. */
    private Reservation reservation(ReservationStatus status, Instant holdExpiresAt) {
        Reservation r = new Reservation(
                UUID.randomUUID(),
                "guest-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 12, 10),
                LocalDate.of(2026, 12, 12),
                2,
                status,
                new BigDecimal("1400.00"),
                holdExpiresAt,
                UUID.randomUUID(),
                "Amina",
                "a@example.ma",
                new CancellationPolicy(false, null));
        rows.put(r.id(), r);
        return r;
    }

    private ReservationStatus status(Reservation r) {
        return rows.get(r.id()).status();
    }

    @Test
    void expiresDueHoldsAndReleasesTheirRoomsOnce() {
        Reservation endedNow = reservation(ReservationStatus.HELD, NOW); // half-open: a hold ending at now is over
        Reservation endedEarlier = reservation(ReservationStatus.HELD, NOW.minusSeconds(60));

        assertThat(service(500).expireDueHolds()).isEqualTo(2);

        assertThat(status(endedNow)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(status(endedEarlier)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(released).containsExactlyInAnyOrder(endedNow.roomTypeId(), endedEarlier.roomTypeId());
        assertThat(rows.get(endedNow.id()).holdExpiresAt()).isEqualTo(NOW); // kept
        assertThat(service(500).expireDueHolds()).isZero(); // a second run finds nothing
        assertThat(released).hasSize(2);
    }

    @Test
    void leavesRunningHoldsAndSettledReservationsAlone() {
        Reservation running = reservation(ReservationStatus.HELD, NOW.plusSeconds(1));
        Reservation confirmed = reservation(ReservationStatus.CONFIRMED, NOW.minusSeconds(60));
        Reservation cancelled = reservation(ReservationStatus.CANCELLED, NOW.minusSeconds(60));

        assertThat(service(500).expireDueHolds()).isZero();

        assertThat(status(running)).isEqualTo(ReservationStatus.HELD);
        assertThat(status(confirmed)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(status(cancelled)).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(released).isEmpty();
    }

    @Test
    void batchSizeCapsOneRunOldestFirstAndTheNextRunDrainsTheRest() {
        Reservation oldest = reservation(ReservationStatus.HELD, NOW.minusSeconds(300));
        Reservation middle = reservation(ReservationStatus.HELD, NOW.minusSeconds(200));
        Reservation newest = reservation(ReservationStatus.HELD, NOW.minusSeconds(100));

        assertThat(service(2).expireDueHolds()).isEqualTo(2);
        assertThat(status(oldest)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(status(middle)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(status(newest)).isEqualTo(ReservationStatus.HELD);

        assertThat(service(2).expireDueHolds()).isEqualTo(1);
        assertThat(status(newest)).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void aRowLockedByAnotherTransactionIsSkippedThenExpiredByTheNextRun() {
        Reservation busy = reservation(ReservationStatus.HELD, NOW.minusSeconds(60));
        lockedElsewhere.add(busy.id());

        assertThat(service(500).expireDueHolds()).isZero();
        assertThat(status(busy)).isEqualTo(ReservationStatus.HELD);

        lockedElsewhere.clear();
        assertThat(service(500).expireDueHolds()).isEqualTo(1);
        assertThat(released).containsExactly(busy.roomTypeId());
    }

    @Test
    void aLostConditionalUpdateReleasesNothing() {
        Reservation raced = reservation(ReservationStatus.HELD, NOW.minusSeconds(60));
        changedAfterLock.add(raced.id());

        assertThat(service(500).expireDueHolds()).isZero();

        assertThat(status(raced)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(released).isEmpty();
    }

    @Test
    void oneFailingHoldIsLoggedAndTheOthersStillExpire() {
        Reservation broken = reservation(ReservationStatus.HELD, NOW.minusSeconds(120));
        Reservation fine = reservation(ReservationStatus.HELD, NOW.minusSeconds(60));
        releaseFails.add(broken.roomTypeId());

        assertThat(service(500).expireDueHolds()).isEqualTo(1);

        assertThat(status(fine)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(released).containsExactly(fine.roomTypeId());
    }

    @Test
    void rejectsABatchSizeBelowOne() {
        assertThatThrownBy(() -> service(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }
}
