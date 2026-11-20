package ma.nuzul.inventory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Setup and helpers shared by the reserve() ITs. Rows are written with plain JDBC (autocommit), so every test
 * starts from committed state that concurrent transactions can see. Each test uses a fresh room type: no cleanup.
 */
public final class ReserveFixtures {

    /* Far enough ahead to stay inside the premade monthly partitions and the booking window, whatever the hour. */
    public static final LocalDate BASE_NIGHT =
            LocalDate.now(ZoneId.of("Africa/Casablanca")).plusDays(30);

    private final JdbcTemplate jdbc;

    public ReserveFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static LocalDate night(int i) {
        return BASE_NIGHT.plusDays(i);
    }

    /** A hotel owned by {@code ownerSub} with one room type; returns the room type id. */
    public UUID roomType(String ownerSub) {
        UUID hotelId = UUID.randomUUID();
        UUID roomTypeId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO hotel (id, owner_sub, name, city) VALUES (?, ?, 'Riad Test', 'Marrakech')",
                hotelId,
                ownerSub);
        jdbc.update(
                "INSERT INTO room_type (id, hotel_id, name, capacity) VALUES (?, ?, 'Double', 2)", roomTypeId, hotelId);
        return roomTypeId;
    }

    public void inventory(UUID roomTypeId, LocalDate night, int total, int reserved) {
        jdbc.update(
                "INSERT INTO room_inventory (room_type_id, night, total, reserved) VALUES (?, ?, ?, ?)",
                roomTypeId,
                night,
                total,
                reserved);
    }

    public void rate(UUID roomTypeId, LocalDate night, String priceMad) {
        jdbc.update(
                "INSERT INTO rate (room_type_id, night, price_mad) VALUES (?, ?, ?::numeric)",
                roomTypeId,
                night,
                priceMad);
    }

    public void deactivateHotelOf(UUID roomTypeId) {
        jdbc.update(
                "UPDATE hotel SET status = 'INACTIVE' WHERE id = (SELECT hotel_id FROM room_type WHERE id = ?)",
                roomTypeId);
    }

    public int reserved(UUID roomTypeId, LocalDate night) {
        return jdbc.queryForObject(
                "SELECT reserved FROM room_inventory WHERE room_type_id = ? AND night = ?",
                Integer.class,
                roomTypeId,
                night);
    }

    public int total(UUID roomTypeId, LocalDate night) {
        return jdbc.queryForObject(
                "SELECT total FROM room_inventory WHERE room_type_id = ? AND night = ?",
                Integer.class,
                roomTypeId,
                night);
    }

    /** Every row of the room type, ordered by night, as one JSON text (null when there are none). */
    public String snapshot(UUID roomTypeId) {
        return jdbc.queryForObject(
                "SELECT json_agg(row_to_json(r) ORDER BY r.night)::text FROM room_inventory r WHERE room_type_id = ?",
                String.class,
                roomTypeId);
    }

    /** Runs {@code work} in its own transaction; whatever it throws rolls the transaction back and is rethrown. */
    public static void inTransaction(TransactionTemplate tx, Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    /** Recorded for a task still running when the race times out (it is then cancelled). */
    public static final class TaskTimedOut extends RuntimeException {
        TaskTimedOut(long seconds) {
            super("task did not finish within " + seconds + " s");
        }
    }

    /**
     * Starts every task at once behind a start gate (each on its own thread, all parked on the gate first) and
     * returns, in submission order, what each one threw (null for success). A task still running after
     * {@code timeoutSeconds} is cancelled and recorded as {@link TaskTimedOut}, so the caller's assertions on the
     * finished tasks (e.g. "no 40P01") still run and say what went wrong.
     */
    public static List<Throwable> race(List<Runnable> tasks, long timeoutSeconds) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    task.run();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        try {
            if (!ready.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new AssertionError("tasks did not all reach the start gate");
            }
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> f : futures) {
                if (f.isDone()) {
                    outcomes.add(f.get());
                } else {
                    f.cancel(true);
                    outcomes.add(new TaskTimedOut(timeoutSeconds));
                }
            }
            return outcomes;
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
    }
}
