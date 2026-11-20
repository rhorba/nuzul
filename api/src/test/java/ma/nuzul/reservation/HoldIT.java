package ma.nuzul.reservation;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.support.AdminApi.PROBLEM_JSON;
import static ma.nuzul.support.AdminApi.admin;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Story 2.1: POST /api/v1/reservations end to end (security, MVC, the hold transaction, Postgres). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HoldIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final String guestSub = "guest-" + UUID.randomUUID();

    private ReserveFixtures fx() {
        return new ReserveFixtures(jdbc);
    }

    /** Three nights stocked with {@code total} rooms and priced 650.00 / 700.00 / 750.00. */
    private UUID roomType(int total) {
        UUID rt = fx().roomType("owner-" + UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            fx().inventory(rt, night(i), total, 0);
            fx().rate(rt, night(i), (650 + 50 * i) + ".00");
        }
        return rt;
    }

    private static String body(UUID roomTypeId, int fromNight, int toNight, int rooms) {
        return """
                {"roomTypeId":"%s","checkIn":"%s","checkOut":"%s","rooms":%d}
                """.formatted(roomTypeId, night(fromNight), night(toNight), rooms);
    }

    private MockHttpServletRequestBuilder hold(String body) {
        return json(post("/api/v1/reservations"), body)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(guest(guestSub));
    }

    @Test
    void holdsTheRoomsAndFreezesThePrice() throws Exception {
        UUID rt = roomType(5);
        Instant before = Instant.now();

        String id = mvc.perform(hold(body(rt, 0, 3, 2)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("http://localhost/api/v1/reservations/")))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.totalMad").value(4200.00))
                .andExpect(jsonPath("$.holdExpiresAt").value(matchesPattern("\\d{4}-.*Z")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM reservation WHERE id = ?::uuid", id.trim());
        assertThat(row.get("status")).isEqualTo("HELD");
        assertThat(row.get("guest_sub")).isEqualTo(guestSub);
        assertThat(row.get("guest_name")).isEqualTo("Amina Guest");
        assertThat(row.get("guest_email")).isEqualTo(guestSub + "@guest.test");
        assertThat((BigDecimal) row.get("total_mad")).isEqualByComparingTo("4200.00");
        assertThat(((Number) row.get("rooms")).intValue()).isEqualTo(2);
        Instant expires = ((java.sql.Timestamp) row.get("hold_expires_at")).toInstant();
        assertThat(Duration.between(before, expires)).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(11));
        for (int i = 0; i < 3; i++) {
            assertThat(fx().reserved(rt, night(i))).isEqualTo(2);
        }
    }

    @Test
    void soldOutIs409AndLeavesNothingBehind() throws Exception {
        UUID rt = roomType(1);
        fx().inventory(rt, night(3), 0, 0);
        fx().rate(rt, night(3), "800.00");

        mvc.perform(hold(body(rt, 1, 4, 1)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE room_type_id = ?", Long.class, rt))
                .isZero();
        assertThat(fx().reserved(rt, night(1))).isZero();
        assertThat(fx().reserved(rt, night(2))).isZero();
    }

    @Test
    void aNightWithoutARateIsNotForSale() throws Exception {
        UUID rt = roomType(5);
        fx().inventory(rt, night(3), 5, 0); // stocked but never priced

        mvc.perform(hold(body(rt, 2, 4, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));
        assertThat(fx().reserved(rt, night(2))).isZero();
    }

    @Test
    void unknownAndInactiveRoomTypesAre404() throws Exception {
        mvc.perform(hold(body(UUID.randomUUID(), 0, 1, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_TYPE_NOT_FOUND"));

        UUID rt = roomType(5);
        fx().deactivateHotelOf(rt);
        mvc.perform(hold(body(rt, 0, 1, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_TYPE_NOT_FOUND"));
        assertThat(fx().reserved(rt, night(0))).isZero();
    }

    @Test
    void invalidRequestsAre400() throws Exception {
        UUID rt = roomType(5);
        String ok = body(rt, 0, 1, 1);

        mvc.perform(json(post("/api/v1/reservations"), ok).with(guest(guestSub)))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/reservations"), ok)
                        .header("Idempotency-Key", "not-a-uuid")
                        .with(guest(guestSub)))
                .andExpect(status().isBadRequest());
        mvc.perform(hold(body(rt, 0, 1, 0))).andExpect(status().isBadRequest());
        mvc.perform(hold(body(rt, 0, 1, 10))).andExpect(status().isBadRequest());
        mvc.perform(hold(body(rt, 1, 1, 1))).andExpect(status().isBadRequest());
        mvc.perform(hold("{\"roomTypeId\":\"" + rt + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(hold("""
                        {"roomTypeId":"%s","checkIn":"2020-01-01","checkOut":"2020-01-02","rooms":1}
                        """.formatted(rt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NIGHT_OUTSIDE_WINDOW"));
        assertThat(fx().reserved(rt, night(0))).isZero();
    }

    @Test
    void onlyAuthenticatedGuestsMayHold() throws Exception {
        UUID rt = roomType(5);
        String ok = body(rt, 0, 1, 1);

        mvc.perform(json(post("/api/v1/reservations"), ok)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/v1/reservations"), ok)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(admin("admin-" + UUID.randomUUID())))
                .andExpect(status().isForbidden());
        assertThat(fx().reserved(rt, night(0))).isZero();
    }

    @Test
    void aTokenWithoutAnEmailIs400() throws Exception {
        UUID rt = roomType(5);

        mvc.perform(json(post("/api/v1/reservations"), body(rt, 0, 1, 1))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(
                                org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.jwt()
                                        .jwt(j -> j.subject(guestSub).claim("preferred_username", "amina"))
                                        .authorities(
                                                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                                        "ROLE_GUEST"))))
                .andExpect(status().isBadRequest());
        assertThat(fx().reserved(rt, night(0))).isZero();
    }
}
