package ma.nuzul.inventory;

import static ma.nuzul.inventory.ReserveFixtures.inTransaction;
import static ma.nuzul.inventory.ReserveFixtures.night;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.shared.error.CodedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Release gate 2 (test strategy): a 3-night stay where one night cannot be sold is SOLD_OUT, and the nights that
 * could have been sold are left exactly as they were. The guarded UPDATE does increment those nights; the rollback
 * triggered by SOLD_OUT is what undoes them, so this proves the all-or-nothing contract end to end.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MultiNightAtomicityIT {

    @Autowired
    InventoryReservations reservations;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    private ReserveFixtures fx;
    private TransactionTemplate tx;
    private UUID rt;

    @BeforeEach
    void setUp() {
        fx = new ReserveFixtures(jdbc);
        tx = new TransactionTemplate(transactionManager);
        rt = fx.roomType("owner-" + UUID.randomUUID());
    }

    /** reserve() over nights 0..2 ([night 0, night 3)) in its own transaction; returns what it threw, or null. */
    private Throwable reserveThreeNights(int rooms) {
        return catchThrowable(() -> inTransaction(tx, () -> reservations.reserve(rt, night(0), night(3), rooms)));
    }

    private static void assertSoldOut(Throwable thrown) {
        assertThat(thrown).isInstanceOf(SoldOutException.class);
        assertThat(((CodedException) thrown).code()).isEqualTo("SOLD_OUT");
    }

    @Test
    void middleNightFullIsSoldOutAndTheOtherNightsAreUnchanged() {
        fx.inventory(rt, night(0), 2, 0);
        fx.inventory(rt, night(1), 2, 2);
        fx.inventory(rt, night(2), 2, 0);
        String before = fx.snapshot(rt);

        assertSoldOut(reserveThreeNights(1));

        assertThat(fx.snapshot(rt)).isEqualTo(before);
        assertThat(fx.reserved(rt, night(0))).isZero();
        assertThat(fx.reserved(rt, night(2))).isZero();
    }

    @Test
    void middleNightWithNoInventoryRowIsSoldOutAndNothingChanges() {
        fx.inventory(rt, night(0), 2, 0);
        fx.inventory(rt, night(2), 2, 0);
        String before = fx.snapshot(rt);

        assertSoldOut(reserveThreeNights(1));

        assertThat(fx.snapshot(rt)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_inventory WHERE room_type_id = ?", Integer.class, rt))
                .isEqualTo(2);
    }

    @Test
    void roomsAboveWhatIsLeftOnOneNightOnlyIsSoldOutAndNothingChanges() {
        fx.inventory(rt, night(0), 3, 0);
        fx.inventory(rt, night(1), 3, 1);
        fx.inventory(rt, night(2), 3, 0);
        String before = fx.snapshot(rt);

        assertSoldOut(reserveThreeNights(3));

        assertThat(fx.snapshot(rt)).isEqualTo(before);
    }

    @Test
    void everyNightAvailableIncrementsAllThreeNightsByRoomsAndLeavesTotal() {
        fx.inventory(rt, night(0), 5, 0);
        fx.inventory(rt, night(1), 5, 2);
        fx.inventory(rt, night(2), 5, 1);
        fx.inventory(rt, night(3), 5, 0);

        assertThat(reserveThreeNights(3)).isNull();

        assertThat(fx.reserved(rt, night(0))).isEqualTo(3);
        assertThat(fx.reserved(rt, night(1))).isEqualTo(5);
        assertThat(fx.reserved(rt, night(2))).isEqualTo(4);
        assertThat(fx.reserved(rt, night(3)))
                .as("checkout day is not a night stayed")
                .isZero();
        for (int i = 0; i < 4; i++) {
            assertThat(fx.total(rt, night(i))).isEqualTo(5);
        }
    }
}
