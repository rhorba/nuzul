package ma.nuzul.reservation;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.support.AdminApi.PROBLEM_JSON;
import static ma.nuzul.support.AdminApi.admin;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.ReserveFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Story 2.5: the per-guest limit on reservation POSTs, end to end (Spring Security, the MVC interceptor, Redis).
 * Capacity 3 per minute here so a test can reach it; each test uses fresh guests, so buckets never carry over.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.rate-limit.reservation-post.capacity=" + RateLimitIT.CAPACITY)
@AutoConfigureMockMvc
class RateLimitIT {

    static final int CAPACITY = 3;
    static final String RESERVATIONS = "/api/v1/reservations";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final String guestSub = "guest-" + UUID.randomUUID();

    private UUID roomType() {
        var fx = new ReserveFixtures(jdbc);
        UUID rt = fx.roomType("owner-" + UUID.randomUUID());
        fx.inventory(rt, night(0), 20, 0);
        fx.rate(rt, night(0), "700.00");
        return rt;
    }

    private static MockHttpServletRequestBuilder hold(UUID roomTypeId) {
        String body = """
                {"roomTypeId":"%s","checkIn":"%s","checkOut":"%s","rooms":1}
                """.formatted(roomTypeId, night(0), night(1));
        return json(post(RESERVATIONS), body)
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private ResultActions holdAs(String sub, UUID roomTypeId) throws Exception {
        return mvc.perform(hold(roomTypeId).with(guest(sub)));
    }

    private static void rateLimited(ResultActions result) throws Exception {
        result.andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", matchesPattern("[1-9]\\d*")));
    }

    private long reservations(String sub) {
        return jdbc.queryForObject("SELECT count(*) FROM reservation WHERE guest_sub = ?", Long.class, sub);
    }

    @Test
    void theRequestOverCapacityIs429AndNeverReachesTheHold() throws Exception {
        UUID rt = roomType();
        for (int i = 0; i < CAPACITY; i++) {
            holdAs(guestSub, rt).andExpect(status().isCreated());
        }

        rateLimited(holdAs(guestSub, rt));

        String retryAfter = holdAs(guestSub, rt).andReturn().getResponse().getHeader("Retry-After");
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
        assertThat(reservations(guestSub)).isEqualTo(CAPACITY);
        assertThat(new ReserveFixtures(jdbc).reserved(rt, night(0))).isEqualTo(CAPACITY);
    }

    @Test
    void anotherGuestHasItsOwnBucket() throws Exception {
        UUID rt = roomType();
        for (int i = 0; i < CAPACITY; i++) {
            holdAs(guestSub, rt).andExpect(status().isCreated());
        }
        rateLimited(holdAs(guestSub, rt));

        holdAs("guest-" + UUID.randomUUID(), rt).andExpect(status().isCreated());
    }

    @Test
    void anInvalidBodyStillCostsAToken() throws Exception {
        for (int i = 0; i < CAPACITY; i++) {
            mvc.perform(json(post(RESERVATIONS), "{\"rooms\":0}")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .with(guest(guestSub)))
                    .andExpect(status().isBadRequest());
        }

        rateLimited(holdAs(guestSub, roomType()));
    }

    @Test
    void unauthenticatedAndForbiddenRequestsTakeNoToken() throws Exception {
        UUID rt = roomType();
        for (int i = 0; i < CAPACITY + 2; i++) {
            mvc.perform(hold(rt)).andExpect(status().isUnauthorized());
            // Same sub as the guest but not a GUEST: refused by the filter chain before the limiter runs.
            mvc.perform(hold(rt).with(admin(guestSub))).andExpect(status().isForbidden());
        }

        for (int i = 0; i < CAPACITY; i++) {
            holdAs(guestSub, rt).andExpect(status().isCreated());
        }
        rateLimited(holdAs(guestSub, rt));
    }

    @Test
    void confirmAndCancelPostsShareTheBucket() throws Exception {
        UUID rt = roomType();
        holdAs(guestSub, rt).andExpect(status().isCreated());
        mvc.perform(json(post(RESERVATIONS + "/" + UUID.randomUUID() + "/confirm"), "{\"paymentToken\":\"tok_visa\"}")
                        .with(guest(guestSub)))
                .andExpect(status().isNotFound());
        mvc.perform(post(RESERVATIONS + "/" + UUID.randomUUID() + "/cancel").with(guest(guestSub)))
                .andExpect(status().isNotFound());

        rateLimited(mvc.perform(
                post(RESERVATIONS + "/" + UUID.randomUUID() + "/cancel").with(guest(guestSub))));
        rateLimited(mvc.perform(json(post(RESERVATIONS + "/" + UUID.randomUUID() + "/confirm"), "{}")
                .with(guest(guestSub))));
        rateLimited(holdAs(guestSub, rt));
        assertThat(reservations(guestSub)).isEqualTo(1);
    }
}
