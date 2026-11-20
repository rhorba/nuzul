package ma.nuzul.reservation;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.inventory.ReserveFixtures.race;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.ReserveFixtures;
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

/**
 * Release gate 3 (test strategy): the same Idempotency-Key sent 5x in parallel gives exactly one reservation, one
 * room taken, and 5 identical 201 responses. {@link IdempotencyRedisDownIT} runs the same tests with Redis
 * unreachable: Postgres alone must give the same guarantee.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyIT {

    static final String RESERVATIONS = "/api/v1/reservations";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    boolean redisUp() {
        return true;
    }

    private MvcResult send(String guestSub, UUID key, String body) {
        try {
            return mvc.perform(json(post(RESERVATIONS), body)
                            .header("Idempotency-Key", key.toString())
                            .with(guest(guestSub)))
                    .andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String body(UUID roomTypeId, int nights, int rooms) {
        return """
                {"roomTypeId":"%s","checkIn":"%s","checkOut":"%s","rooms":%d}
                """.formatted(roomTypeId, night(0), night(nights), rooms);
    }

    private UUID stockedRoomType(int total) {
        var fx = new ReserveFixtures(jdbc);
        UUID rt = fx.roomType("owner-" + UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            fx.inventory(rt, night(i), total, 0);
            fx.rate(rt, night(i), "700.00");
        }
        return rt;
    }

    @Test
    @Timeout(60)
    void fiveParallelRequestsWithOneKeyHoldOnceAndAnswerIdentically() throws Exception {
        UUID rt = stockedRoomType(10);
        String guestSub = "guest-" + UUID.randomUUID();
        UUID key = UUID.randomUUID();
        List<MvcResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> results.add(send(guestSub, key, body(rt, 2, 2))));
        }

        List<Throwable> errors = race(tasks, 30);

        assertThat(errors).containsOnlyNulls();
        assertThat(results).hasSize(5);
        assertThat(results)
                .allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        String first = results.getFirst().getResponse().getContentAsString();
        assertThat(first).contains("\"status\":\"HELD\"").contains("\"totalMad\":2800.00");
        assertThat(results)
                .allSatisfy(
                        r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(first));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM reservation WHERE guest_sub = ? AND idempotency_key = ?",
                        Long.class,
                        guestSub,
                        key))
                .isEqualTo(1);
        var fx = new ReserveFixtures(jdbc);
        assertThat(fx.reserved(rt, night(0))).isEqualTo(2);
        assertThat(fx.reserved(rt, night(1))).isEqualTo(2);
        assertThat(fx.reserved(rt, night(2))).as("check-out night untouched").isZero();
        assertThat(redisHasKey(guestSub, key)).isEqualTo(redisUp());
    }

    @Test
    void aLaterRetryReplaysAndAReusedKeyIsRefused() throws Exception {
        UUID rt = stockedRoomType(10);
        String guestSub = "guest-" + UUID.randomUUID();
        UUID key = UUID.randomUUID();

        String first = send(guestSub, key, body(rt, 2, 1)).getResponse().getContentAsString();
        MvcResult retry = send(guestSub, key, body(rt, 2, 1));
        MvcResult reused = send(guestSub, key, body(rt, 3, 1));

        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first);
        assertThat(reused.getResponse().getStatus()).isEqualTo(422);
        assertThat(reused.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(new ReserveFixtures(jdbc).reserved(rt, night(0))).isEqualTo(1);
    }

    @Test
    void theSameKeyFromAnotherGuestIsAnotherReservation() throws Exception {
        UUID rt = stockedRoomType(10);
        UUID key = UUID.randomUUID();

        String a = send("guest-a-" + UUID.randomUUID(), key, body(rt, 1, 1))
                .getResponse()
                .getContentAsString();
        String b = send("guest-b-" + UUID.randomUUID(), key, body(rt, 1, 1))
                .getResponse()
                .getContentAsString();

        assertThat(a).isNotEqualTo(b);
        assertThat(new ReserveFixtures(jdbc).reserved(rt, night(0))).isEqualTo(2);
    }

    private boolean redisHasKey(String guestSub, UUID key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey("idem:" + guestSub + ":" + key));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
