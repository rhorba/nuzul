package ma.nuzul.inventory;

import static ma.nuzul.inventory.ReserveFixtures.inTransaction;
import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.inventory.ReserveFixtures.race;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Release gate 1 (test strategy): many concurrent requests for the last rooms. Every request runs reserve() in its
 * own transaction, all released at once behind a start gate on 200 threads (50 for the multi-night case). They
 * share a Hikari pool of 50, so up to 50 transactions contend for the row locks in Postgres at once. Exactly the
 * available rooms are sold, every other request is SOLD_OUT, nothing else is thrown, and the database never
 * oversells.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = LastRoomConcurrencyIT.POOL_50)
class LastRoomConcurrencyIT {

    /**
     * A Hikari pool of 50 (default 10) so up to 50 transactions contend in Postgres at once. Shared with
     * ReserveDeadlockFreedomIT: same properties, so one extra Spring context (and container) for both.
     */
    static final String POOL_50 = "spring.datasource.hikari.maximum-pool-size=50";

    private static final long TIMEOUT_SECONDS = 60;

    @Autowired
    InventoryReservations reservations;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    private ReserveFixtures fixtures() {
        return new ReserveFixtures(jdbc);
    }

    private List<Throwable> raceReserve(int requests, UUID roomTypeId, LocalDate checkIn, LocalDate checkOut)
            throws InterruptedException {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            tasks.add(() -> inTransaction(tx, () -> reservations.reserve(roomTypeId, checkIn, checkOut, 1)));
        }
        return race(tasks, TIMEOUT_SECONDS);
    }

    private static void assertOutcomes(List<Throwable> outcomes, int expectedSuccesses) {
        Predicate<Throwable> soldOut = SoldOutException.class::isInstance;
        List<Throwable> unexpected = outcomes.stream()
                .filter(Objects::nonNull)
                .filter(soldOut.negate())
                .toList();

        assertThat(unexpected).as("no exception other than SoldOutException").isEmpty();
        assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(expectedSuccesses);
        assertThat(outcomes.stream().filter(soldOut).count()).isEqualTo(outcomes.size() - expectedSuccesses);
    }

    @Test
    @Timeout(120)
    void twoHundredRequestsForTheLastRoomSellItExactlyOnce() throws Exception {
        var fx = fixtures();
        UUID rt = fx.roomType("owner-" + UUID.randomUUID());
        fx.inventory(rt, night(0), 1, 0);

        List<Throwable> outcomes = raceReserve(200, rt, night(0), night(1));

        assertOutcomes(outcomes, 1);
        assertThat(fx.reserved(rt, night(0))).isEqualTo(1);
        assertThat(fx.total(rt, night(0))).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    void fiftyRequestsForFiveRoomsOverThreeNightsSellExactlyFive() throws Exception {
        var fx = fixtures();
        UUID rt = fx.roomType("owner-" + UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            fx.inventory(rt, night(i), 5, 0);
        }

        List<Throwable> outcomes = raceReserve(50, rt, night(0), night(3));

        assertOutcomes(outcomes, 5);
        for (int i = 0; i < 3; i++) {
            assertThat(fx.reserved(rt, night(i))).as("reserved on night %d", i).isEqualTo(5);
            assertThat(fx.total(rt, night(i))).as("total on night %d", i).isEqualTo(5);
        }
    }
}
