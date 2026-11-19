package ma.nuzul.inventory;

import static ma.nuzul.inventory.ReserveFixtures.inTransaction;
import static ma.nuzul.inventory.ReserveFixtures.night;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * reserve() decides on the values its lock returned, i.e. what the transaction it waited for committed, never on the
 * older version its statement snapshot saw. Transaction A changes a night and holds its lock; reserve() (B) is started
 * and observed blocked on A (pg_blocking_pids, no sleep); A commits; B's outcome must follow A's committed value.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReserveLockWaitIT {

    private static final long WAIT_SECONDS = 20;

    @Autowired
    InventoryReservations reservations;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private ReserveFixtures fx;
    private UUID rt;

    @BeforeEach
    void setUp() {
        fx = new ReserveFixtures(jdbc);
        rt = fx.roomType("owner-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** Opens transaction A, runs {@code sql} on night 0 and returns A still open (its row lock held). */
    private Connection holdingA(String sql) throws SQLException {
        Connection a = dataSource.getConnection();
        a.setAutoCommit(false);
        try (PreparedStatement ps = a.prepareStatement(sql)) {
            ps.setObject(1, rt);
            ps.setObject(2, night(0));
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        return a;
    }

    private static int backendPid(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_backend_pid()");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Polls until some backend waits on a lock held by {@code pid} (B is parked on A's row lock). */
    private void awaitBlockedBy(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid))",
                    Integer.class,
                    pid);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.onSpinWait();
            Thread.sleep(10);
        }
        throw new AssertionError("reserve() never blocked on transaction A's lock");
    }

    /** Starts reserve() of 1 room over nights 0 and 1 in its own transaction; the future yields its exception. */
    private Future<Throwable> reserveTwoNightsInB() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        LocalDate in = night(0);
        LocalDate out = night(2);
        return pool.submit(() -> {
            try {
                inTransaction(tx, () -> reservations.reserve(rt, in, out, 1));
                return null;
            } catch (Throwable t) {
                return t;
            }
        });
    }

    @Test
    @Timeout(60)
    void aRoomReleasedWhileReserveWaitsIsSold() throws Exception {
        fx.inventory(rt, night(0), 1, 1);
        fx.inventory(rt, night(1), 1, 0);

        try (Connection a =
                holdingA("UPDATE room_inventory SET reserved = reserved - 1 WHERE room_type_id = ? AND night = ?")) {
            Future<Throwable> b = reserveTwoNightsInB();
            awaitBlockedBy(backendPid(a));
            a.commit();

            assertThat(b.get(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("B must sell the released room")
                    .isNull();
        }
        assertThat(fx.reserved(rt, night(0))).isEqualTo(1);
        assertThat(fx.reserved(rt, night(1))).isEqualTo(1);
    }

    @Test
    @Timeout(60)
    void aTotalRaisedWhileReserveWaitsIsSold() throws Exception {
        /* The statement snapshot still sees total 1 on a full night: no stale value may reach the CHECK. */
        fx.inventory(rt, night(0), 1, 1);
        fx.inventory(rt, night(1), 1, 0);

        try (Connection a = holdingA("UPDATE room_inventory SET total = 2 WHERE room_type_id = ? AND night = ?")) {
            Future<Throwable> b = reserveTwoNightsInB();
            awaitBlockedBy(backendPid(a));
            a.commit();

            assertThat(b.get(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("B must sell the added room")
                    .isNull();
        }
        assertThat(fx.reserved(rt, night(0))).isEqualTo(2);
        assertThat(fx.total(rt, night(0))).as("the raised total is kept").isEqualTo(2);
        assertThat(fx.reserved(rt, night(1))).isEqualTo(1);
    }

    @Test
    @Timeout(60)
    void aTotalLoweredToReservedWhileReserveWaitsIsSoldOut() throws Exception {
        fx.inventory(rt, night(0), 2, 1);
        fx.inventory(rt, night(1), 2, 0);

        try (Connection a = holdingA("UPDATE room_inventory SET total = 1 WHERE room_type_id = ? AND night = ?")) {
            Future<Throwable> b = reserveTwoNightsInB();
            awaitBlockedBy(backendPid(a));
            a.commit();

            assertThat(b.get(WAIT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(SoldOutException.class);
        }
        assertThat(fx.total(rt, night(0))).isEqualTo(1);
        assertThat(fx.reserved(rt, night(0))).isEqualTo(1);
        assertThat(fx.reserved(rt, night(1))).as("rolled back with SOLD_OUT").isZero();
    }

    @Test
    @Timeout(60)
    void aTotalLoweredButStillAboveReservedWhileReserveWaitsIsSold() throws Exception {
        fx.inventory(rt, night(0), 5, 1);
        fx.inventory(rt, night(1), 5, 0);

        try (Connection a = holdingA("UPDATE room_inventory SET total = 3 WHERE room_type_id = ? AND night = ?")) {
            Future<Throwable> b = reserveTwoNightsInB();
            awaitBlockedBy(backendPid(a));
            a.commit();

            assertThat(b.get(WAIT_SECONDS, TimeUnit.SECONDS)).isNull();
        }
        assertThat(fx.total(rt, night(0))).as("the lowered total is kept").isEqualTo(3);
        assertThat(fx.reserved(rt, night(0))).isEqualTo(2);
        assertThat(fx.reserved(rt, night(1))).isEqualTo(1);
    }

    @Test
    @Timeout(60)
    void theLastRoomTakenWhileReserveWaitsIsSoldOut() throws Exception {
        fx.inventory(rt, night(0), 1, 0);
        fx.inventory(rt, night(1), 1, 0);

        try (Connection a =
                holdingA("UPDATE room_inventory SET reserved = reserved + 1 WHERE room_type_id = ? AND night = ?")) {
            Future<Throwable> b = reserveTwoNightsInB();
            awaitBlockedBy(backendPid(a));
            a.commit();

            assertThat(b.get(WAIT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(SoldOutException.class);
        }
        assertThat(fx.reserved(rt, night(0))).isEqualTo(1);
        assertThat(fx.reserved(rt, night(1))).as("rolled back with SOLD_OUT").isZero();
    }
}
