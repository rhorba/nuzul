package ma.nuzul.reservation;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.inventory.ReserveFixtures.race;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.ReserveFixtures;
import ma.nuzul.inventory.ReserveLastRoomIT;
import ma.nuzul.inventory.SoldOutException;
import ma.nuzul.reservation.application.HoldReservationUseCase;
import ma.nuzul.reservation.domain.Guest;
import ma.nuzul.reservation.domain.HoldRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Release gate 1 (test strategy), through the whole hold: 200 guests race for the last room, each with its own
 * idempotency key, all released at once behind a start gate. Exactly one reservation is HELD, the other 199 are
 * SOLD_OUT with no reservation row left behind, nothing else is thrown, and reserved == total afterwards. Same
 * Spring context as ReserveLastRoomIT (Hikari pool of 50, so up to 50 holds contend in Postgres at once).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = ReserveLastRoomIT.POOL_50)
class LastRoomConcurrencyIT {

    private static final long TIMEOUT_SECONDS = 60;

    @Autowired
    HoldReservationUseCase holds;

    @Autowired
    JdbcTemplate jdbc;

    private List<Throwable> raceHolds(int guests, HoldRequest request) throws InterruptedException {
        String run = UUID.randomUUID().toString();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < guests; i++) {
            Guest guest = new Guest("race-" + run + "-" + i, "Guest " + i, "guest" + i + "@race.test");
            tasks.add(() -> holds.hold(guest, UUID.randomUUID(), request));
        }
        return race(tasks, TIMEOUT_SECONDS);
    }

    private long reservations(UUID roomTypeId, String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE room_type_id = ? AND status = ?",
                Long.class,
                roomTypeId,
                status);
    }

    private static void assertOutcomes(List<Throwable> outcomes, int expectedHolds) {
        Predicate<Throwable> soldOut = SoldOutException.class::isInstance;
        assertThat(outcomes.stream().filter(Objects::nonNull).filter(soldOut.negate()))
                .as("no exception other than SoldOutException (no 40P01, no timeout)")
                .isEmpty();
        assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(expectedHolds);
        assertThat(outcomes.stream().filter(soldOut).count()).isEqualTo(outcomes.size() - expectedHolds);
    }

    @Test
    @Timeout(120)
    void twoHundredGuestsForTheLastRoomGiveExactlyOneHold() throws Exception {
        var fx = new ReserveFixtures(jdbc);
        UUID rt = fx.roomType("owner-" + UUID.randomUUID());
        fx.inventory(rt, night(0), 1, 0);
        fx.rate(rt, night(0), "850.00");

        List<Throwable> outcomes = raceHolds(200, new HoldRequest(rt, night(0), night(1), 1));

        assertOutcomes(outcomes, 1);
        assertThat(reservations(rt, "HELD")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE room_type_id = ?", Long.class, rt))
                .as("sold-out holds roll their reservation row back")
                .isEqualTo(1);
        assertThat(fx.reserved(rt, night(0))).isEqualTo(1);
        assertThat(fx.total(rt, night(0))).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    void fiftyGuestsForFiveRoomsOverThreeNightsGiveExactlyFiveHolds() throws Exception {
        var fx = new ReserveFixtures(jdbc);
        UUID rt = fx.roomType("owner-" + UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            fx.inventory(rt, night(i), 5, 0);
            fx.rate(rt, night(i), "500.00");
        }

        List<Throwable> outcomes = raceHolds(50, new HoldRequest(rt, night(0), night(3), 1));

        assertOutcomes(outcomes, 5);
        assertThat(reservations(rt, "HELD")).isEqualTo(5);
        for (int i = 0; i < 3; i++) {
            assertThat(fx.reserved(rt, night(i))).as("reserved on night %d", i).isEqualTo(5);
        }
    }
}
