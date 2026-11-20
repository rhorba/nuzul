package ma.nuzul.inventory;

import static ma.nuzul.inventory.ReserveFixtures.inTransaction;
import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.inventory.ReserveFixtures.race;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.application.ManageInventoryUseCase;
import ma.nuzul.inventory.domain.InventoryChange;
import ma.nuzul.shared.error.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database doc §3 rule 5: every writer of room_inventory locks nights in ascending order, so concurrent multi-night
 * reserves and bulk total upserts over overlapping nights wait for each other and never deadlock (SQLSTATE 40P01).
 * Writers are submitted in a random order (seed logged; replay with {@code -Dreserve.seed=<seed>}) and released
 * together; the outcome must add up exactly.
 *
 * <p>To make this a regression guard, nothing may lock in night order by accident: the nights are stored in
 * DESCENDING physical (heap) order, and each reserve transaction turns index and bitmap scans off (SET LOCAL, test
 * side only), so a scan returns rows in heap order. Only an explicit ORDER BY night in the locking CTE keeps the
 * lock order ascending.
 *
 * <p>Effective database concurrency: {@value #WRITERS_DESCRIPTION}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = ReserveLastRoomIT.POOL_50)
class ReserveDeadlockFreedomIT {

    private static final String DEADLOCK_DETECTED = "40P01";
    private static final int NIGHTS = 10;
    private static final int INITIAL_TOTAL = 40;
    private static final int RESERVES = 240;
    private static final int UPSERTS = 60;
    private static final long TIMEOUT_SECONDS = 30;
    /* A deadlock is detected after 10 ms instead of 1 s, so a regression fails fast with 40P01, not a timeout. */
    private static final String FAST_DEADLOCK_DETECTION = "SET LOCAL deadlock_timeout = '10ms'";
    static final String WRITERS_DESCRIPTION =
            "300 writer threads share a Hikari pool of 50, so up to 50 transactions run in Postgres at once";

    @Autowired
    InventoryReservations reservations;

    @Autowired
    ManageInventoryUseCase inventoryAdmin;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    /** One reserve request: rooms on nights [from, to) of the test range. */
    record Reserve(int from, int to, int rooms) {
        boolean covers(int night) {
            return night >= from && night < to;
        }
    }

    private static boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && DEADLOCK_DETECTED.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @Timeout(90)
    void overlappingReservesAndBulkUpsertsNeverDeadlockAndAddUp() throws Exception {
        long seed = Long.getLong("reserve.seed", System.nanoTime());
        System.out.println("ReserveDeadlockFreedomIT seed=" + seed);
        Random random = new Random(seed);

        var fx = new ReserveFixtures(jdbc);
        String owner = "owner-" + UUID.randomUUID();
        UUID rt = fx.roomType(owner);
        for (int i = NIGHTS - 1; i >= 0; i--) {
            fx.inventory(rt, night(i), INITIAL_TOTAL, 0);
        }
        /* A no-op update per night, last night first: each appends a new row version in that order. */
        for (int i = NIGHTS - 1; i >= 0; i--) {
            jdbc.update("UPDATE room_inventory SET total = total WHERE room_type_id = ? AND night = ?", rt, night(i));
        }
        assertThat(jdbc.queryForList(
                        "SELECT night FROM room_inventory WHERE room_type_id = ? ORDER BY ctid", LocalDate.class, rt))
                .as("precondition: heap order is the reverse of night order")
                .isSortedAccordingTo(Comparator.reverseOrder());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<Reserve> requests = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < RESERVES; i++) {
            int from = random.nextInt(NIGHTS);
            int to = from + 1 + random.nextInt(NIGHTS - from);
            Reserve r = new Reserve(from, to, 1 + random.nextInt(3));
            requests.add(r);
            tasks.add(() -> inTransaction(tx, () -> {
                jdbc.execute("SET LOCAL enable_indexscan = off");
                jdbc.execute("SET LOCAL enable_bitmapscan = off");
                jdbc.execute(FAST_DEADLOCK_DETECTION);
                reservations.reserve(rt, night(r.from()), night(r.to()), r.rooms());
            }));
        }
        for (int i = 0; i < UPSERTS; i++) {
            int from = random.nextInt(NIGHTS);
            int to = from + 1 + random.nextInt(NIGHTS - from);
            List<InventoryChange> changes = new ArrayList<>();
            for (int n = from; n < to; n++) {
                /* Raises totals; may still be refused (409) when a concurrent upsert raised it further and reserves
                 * have since filled the gap. */
                changes.add(new InventoryChange(night(n), INITIAL_TOTAL + random.nextInt(INITIAL_TOTAL)));
            }
            Collections.shuffle(changes, random);
            requests.add(null);
            /* The service joins this transaction (REQUIRED), so the setting applies to its upsert too. */
            tasks.add(() -> inTransaction(tx, () -> {
                jdbc.execute(FAST_DEADLOCK_DETECTION);
                inventoryAdmin.setTotals(owner, rt, changes);
            }));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            order.add(i);
        }
        Collections.shuffle(order, random);
        List<Runnable> shuffledTasks = order.stream().map(tasks::get).toList();

        long started = System.nanoTime();
        List<Throwable> shuffledOutcomes = race(shuffledTasks, TIMEOUT_SECONDS);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.println("ReserveDeadlockFreedomIT ran " + tasks.size() + " writers in " + elapsedMs + " ms");

        Throwable[] outcomes = new Throwable[tasks.size()];
        for (int i = 0; i < order.size(); i++) {
            outcomes[order.get(i)] = shuffledOutcomes.get(i);
        }

        List<Throwable> failures =
                Arrays.stream(outcomes).filter(Objects::nonNull).toList();
        assertThat(failures).as("no deadlock (40P01), seed " + seed).noneMatch(ReserveDeadlockFreedomIT::isDeadlock);
        assertThat(failures)
                .as("only SOLD_OUT or a 409 conflict, seed " + seed)
                .allMatch(t -> t instanceof SoldOutException
                        || (t instanceof ConflictException c && "INVENTORY_BELOW_RESERVED".equals(c.code())));
        for (int i = 0; i < RESERVES; i++) {
            if (outcomes[i] != null) {
                assertThat(outcomes[i]).as("a failed reserve is SOLD_OUT").isInstanceOf(SoldOutException.class);
            }
        }

        long succeeded =
                IntStream.range(0, RESERVES).filter(i -> outcomes[i] == null).count();
        System.out.println("ReserveDeadlockFreedomIT reserves ok=" + succeeded + "/" + RESERVES + ", upserts ok="
                + IntStream.range(RESERVES, tasks.size())
                        .filter(i -> outcomes[i] == null)
                        .count()
                + "/" + UPSERTS);
        assertThat(succeeded).as("the race must actually sell rooms").isPositive();

        for (int n = 0; n < NIGHTS; n++) {
            int night = n;
            int expected = IntStream.range(0, RESERVES)
                    .filter(i -> outcomes[i] == null && requests.get(i).covers(night))
                    .map(i -> requests.get(i).rooms())
                    .sum();
            assertThat(fx.reserved(rt, night(n)))
                    .as("reserved on night %d == sum of successful reserves covering it", n)
                    .isEqualTo(expected);
            assertThat(fx.reserved(rt, night(n))).isLessThanOrEqualTo(fx.total(rt, night(n)));
        }
    }
}
