package ma.nuzul.inventory;

import static ma.nuzul.inventory.ReserveFixtures.inTransaction;
import static ma.nuzul.inventory.ReserveFixtures.night;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction contract of {@link InventoryReservations#reserve}: it only joins a caller's transaction
 * (MANDATORY), and SOLD_OUT rolls back the caller's whole transaction, including its earlier writes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InventoryReservationsIT {

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
        fx.inventory(rt, night(0), 2, 0);
        fx.inventory(rt, night(1), 2, 2);
    }

    @Test
    void withoutATransactionIsIllegalTransactionStateAndNothingIsWritten() {
        String before = fx.snapshot(rt);

        Throwable thrown = catchThrowable(() -> reservations.reserve(rt, night(0), night(1), 1));

        assertThat(thrown).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(fx.snapshot(rt)).isEqualTo(before);
    }

    @Test
    void soldOutRollsBackTheCallersEarlierReserveInTheSameTransaction() {
        String before = fx.snapshot(rt);

        Throwable thrown = catchThrowable(() -> inTransaction(tx, () -> {
            reservations.reserve(rt, night(0), night(1), 1);
            reservations.reserve(rt, night(1), night(2), 1);
        }));

        assertThat(thrown).isInstanceOf(SoldOutException.class);
        assertThat(fx.snapshot(rt)).isEqualTo(before);
    }

    @Test
    void soldOutCaughtByTheCallerStillRollsBackItsTransaction() {
        String before = fx.snapshot(rt);

        /* reserve() marks the shared transaction rollback-only: swallowing SOLD_OUT cannot commit a half-booking. */
        Throwable thrown = catchThrowable(() -> inTransaction(tx, () -> {
            reservations.reserve(rt, night(0), night(1), 1);
            catchThrowable(() -> reservations.reserve(rt, night(1), night(2), 1));
        }));

        assertThat(thrown).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(fx.snapshot(rt)).isEqualTo(before);
    }

    @Test
    void twoReservesInOneTransactionOnTheSameNightAccumulate() {
        fx.inventory(rt, night(5), 3, 0);

        inTransaction(tx, () -> {
            reservations.reserve(rt, night(5), night(6), 1);
            /* The second call's CTE locks the row this transaction already wrote and must see reserved = 1. */
            reservations.reserve(rt, night(5), night(6), 2);
            assertThat(fx.reserved(rt, night(5)))
                    .as("visible inside the transaction")
                    .isEqualTo(3);
        });

        assertThat(fx.reserved(rt, night(5))).isEqualTo(3);
        assertThat(fx.total(rt, night(5))).isEqualTo(3);
    }

    @Test
    void aThirdReserveInTheSameTransactionPastTotalIsSoldOutAndRollsBackAll() {
        fx.inventory(rt, night(5), 3, 0);

        Throwable thrown = catchThrowable(() -> inTransaction(tx, () -> {
            reservations.reserve(rt, night(5), night(6), 1);
            reservations.reserve(rt, night(5), night(6), 2);
            reservations.reserve(rt, night(5), night(6), 1);
        }));

        assertThat(thrown).isInstanceOf(SoldOutException.class);
        assertThat(fx.reserved(rt, night(5))).isZero();
    }

    @Test
    void invalidInputIsValidationFailedInsideATransaction() {
        Throwable thrown =
                catchThrowable(() -> inTransaction(tx, () -> reservations.reserve(rt, night(0), night(0), 1)));

        assertThat(thrown).isInstanceOf(DomainValidationException.class);
    }
}
