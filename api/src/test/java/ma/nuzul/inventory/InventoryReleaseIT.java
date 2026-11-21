package ma.nuzul.inventory;

import static ma.nuzul.inventory.ReserveFixtures.inTransaction;
import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.inventory.ReserveFixtures.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 2.3: release() against Postgres: all nights or none, the guard, MANDATORY propagation, and release running
 * concurrently with reserve() on the same rows (same lock order: no deadlock, no lost update). Same Spring context as
 * ReserveLastRoomIT (pool of 50).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = ReserveLastRoomIT.POOL_50)
class InventoryReleaseIT {

    @Autowired
    InventoryReservations inventory;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    void releaseGivesBackEveryNightAndNeverTouchesTotal() {
        var fx = new ReserveFixtures(jdbc);
        UUID rt = fx.roomType("owner-" + UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            fx.inventory(rt, night(i), 5, 4);
        }

        inTransaction(tx(), () -> inventory.release(rt, night(0), night(3), 3));

        for (int i = 0; i < 3; i++) {
            assertThat(fx.reserved(rt, night(i))).isEqualTo(1);
            assertThat(fx.total(rt, night(i))).isEqualTo(5);
        }
    }

    @Test
    void aNightWithTooFewReservedRollsTheWholeReleaseBack() {
        var fx = new ReserveFixtures(jdbc);
        UUID rt = fx.roomType("owner-" + UUID.randomUUID());
        fx.inventory(rt, night(0), 5, 2);
        fx.inventory(rt, night(1), 5, 0);
        fx.inventory(rt, night(2), 5, 2);
        String before = fx.snapshot(rt);

        assertThatThrownBy(() -> inTransaction(tx(), () -> inventory.release(rt, night(0), night(3), 1)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(fx.snapshot(rt)).isEqualTo(before);
    }

    @Test
    void releaseOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> inventory.release(UUID.randomUUID(), night(0), night(1), 1))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @Timeout(120)
    void concurrentReservesAndReleasesOverTheSameNightsNeverDeadlockOrLoseAnUpdate() throws Exception {
        var fx = new ReserveFixtures(jdbc);
        UUID rt = fx.roomType("owner-" + UUID.randomUUID());
        for (int i = 0; i < 4; i++) {
            fx.inventory(rt, night(i), 100, 50);
        }
        TransactionTemplate tx = tx();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            // Overlapping, shifted stays: [0,3) and [1,4), so the two kinds of statement contend on nights 1 and 2.
            int from = i % 2;
            boolean release = i % 4 < 2;
            tasks.add(() -> inTransaction(tx, () -> {
                if (release) {
                    inventory.release(rt, night(from), night(from + 3), 1);
                } else {
                    inventory.reserve(rt, night(from), night(from + 3), 1);
                }
            }));
        }

        List<Throwable> outcomes = race(tasks, 60);

        assertThat(outcomes.stream().filter(Objects::nonNull))
                .as("no 40P01, no 23514, no timeout")
                .isEmpty();
        // 20 releases and 20 reserves, half on each stay: every night ends where it started, except where the stays
        // do not overlap: night 0 only sees stay [0,3), night 3 only [1,4); both still balance (10 - 10).
        for (int i = 0; i < 4; i++) {
            assertThat(fx.reserved(rt, night(i))).as("reserved on night %d", i).isEqualTo(50);
        }
    }
}
