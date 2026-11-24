package ma.nuzul.reservation;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.ReserveFixtures;
import ma.nuzul.reservation.application.ExpireHoldsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Story 3.1: reservation events are written to the outbox in the transaction that changes the status, through the
 * real API and Postgres. A replay writes no second event, a SOLD_OUT rollback leaves none behind, and the expiry job
 * writes one per hold it expires.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OutboxEventsIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ExpireHoldsUseCase expireHolds;

    private final String guestSub = "guest-" + UUID.randomUUID();

    /** Two nights stocked with {@code total} rooms at 700.00 each. */
    private UUID roomType(int total) {
        var fx = new ReserveFixtures(jdbc);
        UUID rt = fx.roomType("owner-" + UUID.randomUUID());
        for (int i = 0; i < 2; i++) {
            fx.inventory(rt, night(i), total, 0);
            fx.rate(rt, night(i), "700.00");
        }
        return rt;
    }

    private ResultActions hold(UUID roomTypeId, UUID key) throws Exception {
        return mvc.perform(json(post("/api/v1/reservations"), """
                        {"roomTypeId":"%s","checkIn":"%s","checkOut":"%s","rooms":1}
                        """.formatted(roomTypeId, night(0), night(2)))
                .header("Idempotency-Key", key.toString())
                .with(guest(guestSub)));
    }

    private static String idOf(ResultActions created) throws Exception {
        String body = created.andReturn().getResponse().getContentAsString();
        return body.replaceFirst("^.*\"id\":\"", "").substring(0, 36);
    }

    /** The outbox rows whose payload names this room type, oldest first. */
    private List<Map<String, Object>> events(UUID roomTypeId) {
        return jdbc.queryForList("""
                SELECT aggregate, aggregate_id::text AS aggregate_id, type, payload::text AS payload,
                       payload ->> 'reservationId' AS reservation_id, payload ->> 'hotelId' AS hotel_id,
                       payload ->> 'checkIn' AS check_in, payload ->> 'checkOut' AS check_out,
                       payload ->> 'rooms' AS rooms, payload ->> 'status' AS status,
                       payload ->> 'totalMad' AS total_mad, payload ->> 'occurredAt' AS occurred_at
                FROM outbox_event
                WHERE payload ->> 'roomTypeId' = ?
                ORDER BY created_at, id
                """, roomTypeId.toString());
    }

    @Test
    void aNewHoldWritesOneReservationHeldEventWithoutPersonalData() throws Exception {
        UUID rt = roomType(3);

        String id = idOf(hold(rt, UUID.randomUUID()).andExpect(status().isCreated()));

        var rows = events(rt);
        assertThat(rows).hasSize(1);
        var e = rows.get(0);
        assertThat(e.get("aggregate")).isEqualTo("reservation");
        assertThat(e.get("aggregate_id")).isEqualTo(id);
        assertThat(e.get("type")).isEqualTo("ReservationHeld");
        assertThat(e.get("reservation_id")).isEqualTo(id);
        assertThat(e.get("hotel_id"))
                .isEqualTo(jdbc.queryForObject(
                        "SELECT hotel_id::text FROM reservation WHERE id = ?::uuid", String.class, id));
        assertThat(e.get("check_in")).isEqualTo(night(0).toString());
        assertThat(e.get("check_out")).isEqualTo(night(2).toString());
        assertThat(e.get("rooms")).isEqualTo("1");
        assertThat(e.get("status")).isEqualTo("HELD");
        assertThat(e.get("total_mad")).isEqualTo("1400.00");
        assertThat(e.get("occurred_at")).isNotNull();
        assertThat((String) e.get("payload"))
                .doesNotContain("guest", "Guest", "email", "idempotency", "Idempotency", guestSub)
                .doesNotContain("@guest.test");
    }

    @Test
    void replayingTheSameKeyWritesNoSecondEvent() throws Exception {
        UUID rt = roomType(3);
        UUID key = UUID.randomUUID();
        int first = hold(rt, key)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getStatus();
        // Drop the Redis entry (RedisIdempotencyCache's key format) so the replay reaches HoldTransaction's
        // Postgres look-up under the guest lock, the path that must not append a second event.
        redis.delete("idem:" + guestSub + ":" + key);

        hold(rt, key).andExpect(status().is(first));

        assertThat(events(rt)).hasSize(1);
    }

    @Test
    void aSoldOutHoldRollsItsEventBackWithTheReservation() throws Exception {
        UUID rt = roomType(0);

        hold(rt, UUID.randomUUID()).andExpect(status().isConflict());

        assertThat(events(rt)).isEmpty();
    }

    @Test
    void aCancelWritesAReservationCancelledEvent() throws Exception {
        UUID rt = roomType(3);
        String id = idOf(hold(rt, UUID.randomUUID()).andExpect(status().isCreated()));

        mvc.perform(post("/api/v1/reservations/" + id + "/cancel").with(guest(guestSub)))
                .andExpect(status().isOk());

        var rows = events(rt);
        assertThat(rows).extracting(r -> r.get("type")).containsExactly("ReservationHeld", "ReservationCancelled");
        var cancelled = rows.get(1);
        assertThat(cancelled.get("aggregate_id")).isEqualTo(id);
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void anExpiredHoldWritesAReservationExpiredEvent() throws Exception {
        UUID rt = roomType(3);
        String id = idOf(hold(rt, UUID.randomUUID()).andExpect(status().isCreated()));
        jdbc.update("UPDATE reservation SET hold_expires_at = now() - interval '1 second' WHERE id = ?::uuid", id);

        expireHolds.expireDueHolds();

        var rows = events(rt);
        assertThat(rows).extracting(r -> r.get("type")).containsExactly("ReservationHeld", "ReservationExpired");
        var expired = rows.get(1);
        assertThat(expired.get("aggregate_id")).isEqualTo(id);
        assertThat(expired.get("status")).isEqualTo("EXPIRED");
    }
}
