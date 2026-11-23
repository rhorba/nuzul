package ma.nuzul.reservation;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.inventory.ReserveFixtures.race;
import static ma.nuzul.support.AdminApi.PROBLEM_JSON;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.ReserveFixtures;
import ma.nuzul.inventory.ReserveLastRoomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Story 2.5: at most 5 unpaid holds per guest (the application.yml default), exact under concurrency because the
 * hold transaction takes a per-guest advisory lock before it counts. Each test uses a fresh guest.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = ReserveLastRoomIT.POOL_50) // 10 holds + connection A + the polling reads
@AutoConfigureMockMvc
class ActiveHoldCapIT {

    static final int MAX = 5;
    static final String RESERVATIONS = "/api/v1/reservations";
    static final int PARALLEL = 10;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    DataSource dataSource;

    private final String guestSub = "guest-" + UUID.randomUUID();
    private UUID roomTypeId;

    private ReserveFixtures fx() {
        return new ReserveFixtures(jdbc);
    }

    /** One night with plenty of rooms, so only the cap can refuse a hold. */
    private void roomType() {
        roomTypeId = fx().roomType("owner-" + UUID.randomUUID());
        fx().inventory(roomTypeId, night(0), 50, 0);
        fx().rate(roomTypeId, night(0), "700.00");
    }

    private ResultActions hold(UUID key) throws Exception {
        String body = """
                {"roomTypeId":"%s","checkIn":"%s","checkOut":"%s","rooms":1}
                """.formatted(roomTypeId, night(0), night(1));
        return mvc.perform(json(post(RESERVATIONS), body)
                .header("Idempotency-Key", key.toString())
                .with(guest(guestSub)));
    }

    private String holdOk(UUID key) throws Exception {
        return hold(key)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String holdOk() throws Exception {
        return holdOk(UUID.randomUUID());
    }

    private static void capped(ResultActions result) throws Exception {
        result.andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TOO_MANY_ACTIVE_HOLDS"))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    private long held() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE guest_sub = ? AND status = 'HELD'", Long.class, guestSub);
    }

    private int reserved() {
        return fx().reserved(roomTypeId, night(0));
    }

    @Test
    void theSixthActiveHoldIsRefusedAndTakesNoRoom() throws Exception {
        roomType();
        for (int i = 0; i < MAX; i++) {
            holdOk();
        }

        capped(hold(UUID.randomUUID()));

        assertThat(held()).isEqualTo(MAX);
        assertThat(reserved()).isEqualTo(MAX);
    }

    @Test
    void onlyHoldsStillRunningCount() throws Exception {
        roomType();
        Instant past = Instant.now().minus(Duration.ofMinutes(1));
        Instant future = Instant.now().plus(Duration.ofMinutes(10));
        fx().reservation(roomTypeId, guestSub, "HELD", past); // over in time, not yet swept by the expiry job
        fx().reservation(roomTypeId, guestSub, "CONFIRMED", future);
        fx().reservation(roomTypeId, guestSub, "CANCELLED", future);
        fx().reservation(roomTypeId, guestSub, "EXPIRED", past);

        for (int i = 0; i < MAX; i++) {
            holdOk();
        }

        capped(hold(UUID.randomUUID()));
    }

    @Test
    void cancellingAHoldFreesASlot() throws Exception {
        roomType();
        String first = holdOk();
        for (int i = 1; i < MAX; i++) {
            holdOk();
        }
        capped(hold(UUID.randomUUID()));
        String id = first.replaceFirst("^.*\"id\":\"", "").substring(0, 36);

        mvc.perform(post(RESERVATIONS + "/" + id + "/cancel").with(guest(guestSub)))
                .andExpect(status().isOk());

        holdOk();
        assertThat(held()).isEqualTo(MAX);
    }

    @Test
    void aReplayAtTheCapAnswersTheFirstResponse() throws Exception {
        roomType();
        UUID key = UUID.randomUUID();
        String first = holdOk(key);
        for (int i = 1; i < MAX; i++) {
            holdOk();
        }
        redis.delete("idem:" + guestSub + ":" + key); // replayed from Postgres, not from the cache

        assertThat(holdOk(key)).isEqualTo(first);
        assertThat(held()).isEqualTo(MAX);
        assertThat(reserved()).isEqualTo(MAX);
    }

    /**
     * The 10 requests are parked on a lock of the room's inventory row (held by connection A) until all 10 sessions
     * wait in Postgres, so their transactions overlap for sure. Without the guest lock each would count 4 before any
     * insert and all 10 would hold; with it the first holds the guest lock while it waits on A, the other 9 wait
     * behind it, then count 5 and are refused.
     */
    @Test
    @Timeout(90)
    void tenParallelHoldsAtFourActiveGiveExactlyOneMore() throws Exception {
        roomType();
        for (int i = 0; i < MAX - 1; i++) {
            holdOk();
        }
        List<MvcResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            tasks.add(() -> {
                try {
                    results.add(hold(UUID.randomUUID()).andReturn());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        List<Throwable> outcomes;
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try (Connection a = lockingInventoryRow()) {
            Future<List<Throwable>> race = runner.submit(() -> race(tasks, 60));
            awaitWaitingSessions(PARALLEL);
            a.rollback();
            outcomes = race.get(70, TimeUnit.SECONDS);
        } finally {
            runner.shutdownNow();
        }

        assertThat(outcomes).containsOnlyNulls();
        assertThat(results).hasSize(PARALLEL);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201))
                .hasSize(1);
        assertThat(results.stream()
                        .filter(r -> r.getResponse().getStatus() == 429)
                        .filter(r -> contentOf(r).contains("TOO_MANY_ACTIVE_HOLDS")))
                .hasSize(PARALLEL - 1);
        assertThat(held()).isEqualTo(MAX);
        assertThat(reserved()).isEqualTo(MAX);
    }

    /** Connection A, in an open transaction holding the row lock on the room's inventory for night 0. */
    private Connection lockingInventoryRow() throws SQLException {
        Connection a = dataSource.getConnection();
        a.setAutoCommit(false);
        try (PreparedStatement ps =
                a.prepareStatement("SELECT 1 FROM room_inventory WHERE room_type_id = ? AND night = ? FOR UPDATE")) {
            ps.setObject(1, roomTypeId);
            ps.setObject(2, night(0));
            ps.executeQuery().close();
        }
        return a;
    }

    /** Polls until {@code count} sessions of this database wait on a lock (row or advisory). */
    private void awaitWaitingSessions(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Integer waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("only " + waiting + " of " + count + " holds reached Postgres and waited");
    }

    private static String contentOf(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
