package ma.nuzul.reservation;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.inventory.ReserveFixtures.race;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.InventoryReservations;
import ma.nuzul.inventory.ReserveFixtures;
import ma.nuzul.reservation.application.ExpireHoldsUseCase;
import ma.nuzul.reservation.application.ReservationRepositoryPort;
import ma.nuzul.reservation.domain.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Release gate 4 (story 2.4): an expired hold releases its inventory exactly once, even with schedulers racing each
 * other, a confirm or a cancel. The runs call the use case directly, without the ShedLock lock (the worst case: two
 * replicas both running); the scheduled wiring is HoldExpirySchedulingIT.
 *
 * <p>Every test uses its own room type with {@link #OTHERS} rooms reserved by bookings that are not expiring: a
 * release done twice would eat into them, so {@code reserved == OTHERS} afterwards proves exactly-once.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Timeout(120)
class HoldExpiryIT {

    private static final int OTHERS = 10;

    @Autowired
    ExpireHoldsUseCase expireHolds;

    @Autowired
    ReservationRepositoryPort reservations;

    @Autowired
    InventoryReservations inventory;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    private final String guestSub = "guest-" + UUID.randomUUID();
    private UUID roomTypeId;
    private ReserveFixtures fx;

    /** One night (night(0)) with room for {@code holds} one-room holds plus the other bookings, all reserved. */
    private void roomType(int holds) {
        fx = new ReserveFixtures(jdbc);
        roomTypeId = fx.roomType("owner-" + UUID.randomUUID());
        fx.inventory(roomTypeId, night(0), holds + OTHERS + 5, holds + OTHERS);
    }

    private UUID hold(Instant expiresAt) {
        return fx.reservation(roomTypeId, guestSub, "HELD", expiresAt);
    }

    private static Instant ago(int seconds) {
        return Instant.now().minusSeconds(seconds);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id);
    }

    private int reserved() {
        return fx.reserved(roomTypeId, night(0));
    }

    private int countWithStatus(String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE room_type_id = ? AND status = ?",
                Integer.class,
                roomTypeId,
                status);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    void fiftyExpiredHoldsAreReleasedExactlyOnceByFourRacingSchedulers() throws Exception {
        roomType(50);
        for (int i = 0; i < 50; i++) {
            hold(ago(60 + i));
        }
        AtomicInteger expiredByRuns = new AtomicInteger();
        List<Runnable> schedulers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            schedulers.add(() -> expiredByRuns.addAndGet(expireHolds.expireDueHolds()));
        }

        List<Throwable> outcomes = race(schedulers, 60);

        assertThat(outcomes).containsOnlyNulls();
        assertThat(countWithStatus("EXPIRED")).isEqualTo(50);
        assertThat(countWithStatus("HELD")).isZero();
        assertThat(reserved()).isEqualTo(OTHERS);
        // Other test classes may leave due holds in the shared database: the runs expired ours plus maybe theirs.
        assertThat(expiredByRuns.get()).isGreaterThanOrEqualTo(50);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM reservation WHERE room_type_id = ? AND hold_expires_at IS NULL",
                        Integer.class,
                        roomTypeId))
                .isZero(); // hold_expires_at kept
    }

    @Test
    void aHoldStillRunningIsLeftAlone() {
        roomType(1);
        UUID running = hold(Instant.now().plusSeconds(600));

        expireHolds.expireDueHolds();

        assertThat(status(running)).isEqualTo("HELD");
        assertThat(reserved()).isEqualTo(OTHERS + 1);
    }

    @Test
    void aConfirmHoldingTheRowWinsAndTheRunSkipsItInsteadOfWaiting() throws Exception {
        roomType(1);
        UUID id = hold(ago(1)); // the confirm read it just before the deadline; expiry sees it just after
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch runDone = new CountDownLatch(1);
        Thread confirm = Thread.ofVirtual()
                .start(() -> tx().executeWithoutResult(s -> {
                    jdbc.update("UPDATE reservation SET status = 'CONFIRMED' WHERE id = ? AND status = 'HELD'", id);
                    rowLocked.countDown();
                    await(runDone);
                }));
        assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        expireHolds.expireDueHolds(); // SKIP LOCKED: returns at once instead of queueing behind the confirm
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        runDone.countDown();
        confirm.join(10_000);

        assertThat(tookMillis).isLessThan(5_000);
        assertThat(status(id)).isEqualTo("CONFIRMED");
        expireHolds.expireDueHolds(); // the next run sees it CONFIRMED: nothing to do
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(reserved()).isEqualTo(OTHERS + 1); // a confirmed stay keeps its room
    }

    @Test
    void cancelsAndRunsRacingOnTheSameHoldsReleaseEachOnce() throws Exception {
        roomType(20);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(hold(ago(30)));
        }
        List<Runnable> tasks = new ArrayList<>();
        tasks.add(expireHolds::expireDueHolds);
        tasks.add(expireHolds::expireDueHolds);
        for (UUID id : ids) {
            // What CancelTransaction does for a HELD reservation: conditional status change + release, one commit.
            tasks.add(() -> tx().executeWithoutResult(s -> {
                if (reservations.cancelIf(id, ReservationStatus.HELD)) {
                    inventory.release(roomTypeId, night(0), night(1), 1);
                }
            }));
        }

        List<Throwable> outcomes = race(tasks, 60);

        assertThat(outcomes).containsOnlyNulls(); // no deadlock: reservation row first, inventory second, everywhere
        assertThat(countWithStatus("EXPIRED") + countWithStatus("CANCELLED")).isEqualTo(20);
        assertThat(reserved()).isEqualTo(OTHERS);
    }

    @Test
    void anExpiredHoldCannotBeConfirmedOrCancelledAfterwards() {
        roomType(1);
        UUID id = hold(ago(5));

        expireHolds.expireDueHolds();

        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(reservations.confirmIfHeld(id, Instant.now())).isFalse();
        assertThat(reservations.cancelIf(id, ReservationStatus.HELD)).isFalse();
        assertThat(reserved()).isEqualTo(OTHERS);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the expiry run");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
