package ma.nuzul.reservation;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.inventory.ReserveFixtures.race;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.ReserveFixtures;
import ma.nuzul.payment.ChargeResult;
import ma.nuzul.payment.application.PaymentPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Story 2.3: POST /api/v1/reservations/{id}/cancel end to end, with real holds and confirms through the API. The
 * provider is a stand-in with the fake adapter's rules plus a switch to make refunds fail.
 */
@Import({TestcontainersConfiguration.class, CancelIT.SwitchableProvider.class})
@SpringBootTest
@AutoConfigureMockMvc
class CancelIT {

    /** Distinct refund idempotency keys the provider saw: one per refund actually made (it deduplicates). */
    static final java.util.Set<String> REFUND_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static volatile boolean refundsFail;
    /** Runs inside the provider charge: lets a test cancel the hold while it is being paid. */
    static volatile Runnable duringCharge = () -> {};

    @TestConfiguration(proxyBeanMethods = false)
    static class SwitchableProvider {
        @Bean
        @Primary
        PaymentPort switchablePaymentPort() {
            return new PaymentPort() {
                @Override
                public ChargeResult charge(String idempotencyKey, BigDecimal amountMad, String paymentToken) {
                    duringCharge.run();
                    return new ChargeResult.Approved("fake_ch_" + idempotencyKey);
                }

                @Override
                public String refund(String idempotencyKey, String chargeRef, BigDecimal amountMad) {
                    if (refundsFail) {
                        throw new IllegalStateException("provider timeout");
                    }
                    REFUND_KEYS.add(idempotencyKey);
                    return "fake_re_" + idempotencyKey;
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final String guestSub = "guest-" + UUID.randomUUID();
    private UUID roomTypeId;

    @AfterEach
    void providerWorks() {
        refundsFail = false;
        duringCharge = () -> {};
    }

    /** A hold through the API on 2 nights at 700.00; {@code refundable} and {@code hours} go on both rates. */
    private String hold(boolean refundable, int hours) throws Exception {
        var fx = new ReserveFixtures(jdbc);
        roomTypeId = fx.roomType("owner-" + UUID.randomUUID());
        for (int i = 0; i < 2; i++) {
            fx.inventory(roomTypeId, night(i), 3, 0);
            fx.rate(roomTypeId, night(i), "700.00");
        }
        jdbc.update(
                "UPDATE rate SET refundable = ?, free_cancel_hours = ? WHERE room_type_id = ?",
                refundable,
                hours,
                roomTypeId);
        String body = mvc.perform(json(post("/api/v1/reservations"), """
                                {"roomTypeId":"%s","checkIn":"%s","checkOut":"%s","rooms":1}
                                """.formatted(roomTypeId, night(0), night(2)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(guest(guestSub)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return body.replaceFirst("^.*\"id\":\"", "").substring(0, 36);
    }

    private String confirmed(boolean refundable, int hours) throws Exception {
        String id = hold(refundable, hours);
        mvc.perform(json(post("/api/v1/reservations/" + id + "/confirm"), "{\"paymentToken\":\"tok_visa_4242\"}")
                        .with(guest(guestSub)))
                .andExpect(status().isOk());
        return id;
    }

    private ResultActions cancel(String id, String asGuest) throws Exception {
        return mvc.perform(post("/api/v1/reservations/" + id + "/cancel").with(guest(asGuest)));
    }

    private int reserved(int night) {
        return new ReserveFixtures(jdbc).reserved(roomTypeId, night(night));
    }

    private List<String> payments(String id) {
        return jdbc.queryForList("SELECT status FROM payment WHERE reservation_id = ?::uuid", String.class, id);
    }

    @Test
    void theHoldFreezesTheCancellationPolicy() throws Exception {
        String id = hold(true, 72);
        jdbc.update("UPDATE rate SET refundable = false WHERE room_type_id = ?", roomTypeId); // a later admin edit

        var row = jdbc.queryForMap("SELECT refundable, free_cancel_until FROM reservation WHERE id = ?::uuid", id);
        assertThat(row.get("refundable")).isEqualTo(true);
        Instant expected = night(0).atStartOfDay(ZoneId.of("Africa/Casablanca"))
                .toInstant()
                .minusSeconds(72 * 3600);
        assertThat(((java.sql.Timestamp) row.get("free_cancel_until")).toInstant())
                .isEqualTo(expected);
    }

    @Test
    void aFreeCancelReleasesTheRoomsAndRefundsInFull() throws Exception {
        String id = confirmed(true, 48);
        assertThat(reserved(0)).isEqualTo(1);
        int refundsBefore = REFUND_KEYS.size();

        cancel(id, guestSub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund").value("COMPLETED"))
                .andExpect(jsonPath("$.refundMad").value(1400.00));

        assertThat(reserved(0)).isZero();
        assertThat(reserved(1)).isZero();
        assertThat(payments(id)).containsExactly("REFUNDED");
        assertThat(REFUND_KEYS).hasSize(refundsBefore + 1);

        cancel(id, guestSub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund").value("COMPLETED"));
        assertThat(reserved(0)).as("released once").isZero();
        assertThat(REFUND_KEYS).hasSize(refundsBefore + 1);
    }

    @Test
    void aNonRefundableStayIsCancelledWithoutRefund() throws Exception {
        String id = confirmed(false, 48);

        cancel(id, guestSub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund").value("NONE"))
                .andExpect(jsonPath("$.refundMad").value(0.00));

        assertThat(reserved(0)).isZero();
        assertThat(payments(id)).containsExactly("CAPTURED");
    }

    @Test
    void pastTheDeadlineThereIsNoRefund() throws Exception {
        String id = confirmed(true, 48);
        jdbc.update("UPDATE reservation SET free_cancel_until = now() - interval '1 second' WHERE id = ?::uuid", id);

        cancel(id, guestSub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund").value("NONE"));
        assertThat(payments(id)).containsExactly("CAPTURED");
        assertThat(reserved(0)).isZero();
    }

    @Test
    void aCancelledHoldFreesItsRoomsAndCanNoLongerBeConfirmed() throws Exception {
        String id = hold(true, 48);

        cancel(id, guestSub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund").value("NONE"));
        assertThat(reserved(0)).isZero();

        mvc.perform(json(post("/api/v1/reservations/" + id + "/confirm"), "{\"paymentToken\":\"tok\"}")
                        .with(guest(guestSub)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CANCELLED"));
        assertThat(payments(id)).isEmpty();
    }

    @Test
    void aProviderErrorStillCancelsAndARetryFinishesTheRefund() throws Exception {
        String id = confirmed(true, 48);
        refundsFail = true;

        cancel(id, guestSub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund").value("PENDING"));
        assertThat(payments(id)).containsExactly("REFUNDING");
        assertThat(reserved(0)).isZero();

        refundsFail = false;
        cancel(id, guestSub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund").value("COMPLETED"));
        assertThat(payments(id)).containsExactly("REFUNDED");
    }

    @Test
    @Timeout(60)
    void fiveParallelCancelsReleaseAndRefundOnce() throws Exception {
        String id = confirmed(true, 48);
        int refundsBefore = REFUND_KEYS.size();
        List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> {
                try {
                    var r = cancel(id, guestSub).andReturn().getResponse();
                    bodies.add(r.getStatus() + " " + r.getContentAsString());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        assertThat(race(tasks, 30)).containsOnlyNulls();
        assertThat(bodies)
                .hasSize(5)
                .allSatisfy(b -> assertThat(b).startsWith("200 ").contains("\"CANCELLED\""));
        assertThat(reserved(0)).isZero();
        assertThat(reserved(1)).isZero();
        assertThat(payments(id)).containsExactly("REFUNDED");
        assertThat(REFUND_KEYS).hasSize(refundsBefore + 1);
    }

    @Test
    void aCancelThatLandsWhileTheHoldIsBeingPaidWinsAndTheChargeIsRefunded() throws Exception {
        String id = hold(true, 48);
        duringCharge = () -> {
            duringCharge = () -> {};
            try {
                cancel(id, guestSub)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.refund").value("NONE"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        mvc.perform(json(post("/api/v1/reservations/" + id + "/confirm"), "{\"paymentToken\":\"tok\"}")
                        .with(guest(guestSub)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CANCELLED"));

        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?::uuid", String.class, id))
                .isEqualTo("CANCELLED");
        assertThat(payments(id)).as("charged during the race, then given back").containsExactly("REFUNDED");
        assertThat(reserved(0)).isZero();
        cancel(id, guestSub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund").value("COMPLETED"));
    }

    @Test
    void expiredIs410AndAnotherGuestsIs404() throws Exception {
        String id = hold(true, 48);

        cancel(id, "intruder-" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        assertThat(reserved(0)).isEqualTo(1);

        jdbc.update("UPDATE reservation SET status = 'EXPIRED' WHERE id = ?::uuid", id);
        cancel(id, guestSub)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        mvc.perform(post("/api/v1/reservations/" + id + "/cancel")).andExpect(status().isUnauthorized());
    }
}
