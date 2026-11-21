package ma.nuzul.reservation;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.inventory.ReserveFixtures.race;
import static ma.nuzul.support.AdminApi.PROBLEM_JSON;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Story 2.2: POST /api/v1/reservations/{id}/confirm end to end. The provider is a scripted stand-in with the fake
 * adapter's rules (token ending 0002 declines, refs derived from the idempotency key) plus a hook that runs during the
 * charge, so a test can expire the hold while it is being paid.
 */
@Import({TestcontainersConfiguration.class, ConfirmIT.HookedProvider.class})
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmIT {

    static final AtomicInteger CHARGES = new AtomicInteger();
    static final java.util.Set<String> CHARGE_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static volatile Consumer<String> duringCharge = key -> {};

    @TestConfiguration(proxyBeanMethods = false)
    static class HookedProvider {
        @Bean
        @Primary
        PaymentPort hookedPaymentPort() {
            return new PaymentPort() {
                @Override
                public ChargeResult charge(String idempotencyKey, BigDecimal amountMad, String paymentToken) {
                    CHARGES.incrementAndGet();
                    CHARGE_KEYS.add(idempotencyKey);
                    duringCharge.accept(idempotencyKey);
                    return paymentToken.endsWith("0002")
                            ? new ChargeResult.Declined("card declined")
                            : new ChargeResult.Approved("fake_ch_" + idempotencyKey);
                }

                @Override
                public String refund(String chargeRef, BigDecimal amountMad) {
                    return "fake_re_" + chargeRef;
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
    void resetHook() {
        duringCharge = key -> {};
    }

    /** A real hold through the API: 2 nights at 700.00, 1 room. */
    private String hold() throws Exception {
        var fx = new ReserveFixtures(jdbc);
        roomTypeId = fx.roomType("owner-" + UUID.randomUUID());
        for (int i = 0; i < 2; i++) {
            fx.inventory(roomTypeId, night(i), 3, 0);
            fx.rate(roomTypeId, night(i), "700.00");
        }
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

    private ResultActions confirm(String id, String token, String asGuest) throws Exception {
        return mvc.perform(json(post("/api/v1/reservations/" + id + "/confirm"), "{\"paymentToken\":\"" + token + "\"}")
                .with(guest(asGuest)));
    }

    private String reservationStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?::uuid", String.class, id);
    }

    private List<String> paymentStatuses(String id) {
        return jdbc.queryForList(
                "SELECT status FROM payment WHERE reservation_id = ?::uuid ORDER BY created_at", String.class, id);
    }

    @Test
    void confirmChargesOnceAndCapturesWithTheConfirmation() throws Exception {
        String id = hold();

        confirm(id, "tok_visa_4242", guestSub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalMad").value(1400.00));

        assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
        assertThat(paymentStatuses(id)).containsExactly("CAPTURED");
        assertThat(jdbc.queryForObject(
                        "SELECT amount_mad FROM payment WHERE reservation_id = ?::uuid", BigDecimal.class, id))
                .isEqualByComparingTo("1400.00");
        assertThat(jdbc.queryForObject(
                        "SELECT hold_expires_at IS NOT NULL FROM reservation WHERE id = ?::uuid", Boolean.class, id))
                .as("kept for idempotent hold replays")
                .isTrue();
        assertThat(new ReserveFixtures(jdbc).reserved(roomTypeId, night(0)))
                .as("confirm never touches inventory")
                .isEqualTo(1);

        confirm(id, "tok_visa_4242", guestSub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(paymentStatuses(id)).containsExactly("CAPTURED");
    }

    @Test
    void aDeclinedCardIs402AndAnotherCardCanStillPay() throws Exception {
        String id = hold();

        confirm(id, "tok_visa_0002", guestSub)
                .andExpect(status().isPaymentRequired())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_FAILED"));
        assertThat(reservationStatus(id)).isEqualTo("HELD");

        confirm(id, "tok_visa_4242", guestSub).andExpect(status().isOk());
        assertThat(paymentStatuses(id)).containsExactly("FAILED", "CAPTURED");
    }

    @Test
    void anExpiredHoldIs410WithoutCharging() throws Exception {
        String id = hold();
        jdbc.update("UPDATE reservation SET hold_expires_at = now() - interval '1 second' WHERE id = ?::uuid", id);
        int before = CHARGES.get();

        confirm(id, "tok_visa_4242", guestSub)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        assertThat(CHARGES.get()).isEqualTo(before);
        assertThat(paymentStatuses(id)).isEmpty();
    }

    @Test
    void aHoldThatExpiresWhileBeingPaidIsRefundedAnd410() throws Exception {
        String id = hold();
        duringCharge = key -> jdbc.update("UPDATE reservation SET status = 'EXPIRED' WHERE id = ?::uuid", id);

        confirm(id, "tok_visa_4242", guestSub)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));

        assertThat(reservationStatus(id)).isEqualTo("EXPIRED");
        assertThat(paymentStatuses(id)).containsExactly("REFUNDED");
    }

    @Test
    @Timeout(60)
    void fiveParallelConfirmsChargeOnceAndAllSucceed() throws Exception {
        String id = hold();
        CHARGE_KEYS.clear();
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> {
                try {
                    MvcResult r = confirm(id, "tok_visa_4242", guestSub).andReturn();
                    statuses.add(r.getResponse().getStatus());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        assertThat(race(tasks, 30)).containsOnlyNulls();
        assertThat(statuses).containsOnly(200).hasSize(5);
        assertThat(paymentStatuses(id)).containsExactly("CAPTURED");
        assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
        assertThat(CHARGE_KEYS)
                .as("every charge used the one payment's idempotency key")
                .hasSize(1);
    }

    @Test
    void anotherGuestsReservationIs404AndACancelledOneIs409() throws Exception {
        String id = hold();

        confirm(id, "tok_visa_4242", "intruder-" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        assertThat(reservationStatus(id)).isEqualTo("HELD");

        jdbc.update("UPDATE reservation SET status = 'CANCELLED' WHERE id = ?::uuid", id);
        confirm(id, "tok_visa_4242", guestSub)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CANCELLED"));
        assertThat(paymentStatuses(id)).isEmpty();
    }

    @Test
    void invalidRequestsAre400Or401() throws Exception {
        String id = hold();

        confirm(id, " ", guestSub).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/reservations/" + id + "/confirm"), "{}").with(guest(guestSub)))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/reservations/not-a-uuid/confirm"), "{\"paymentToken\":\"t\"}")
                        .with(guest(guestSub)))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/reservations/" + id + "/confirm"), "{\"paymentToken\":\"t\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(reservationStatus(id)).isEqualTo("HELD");
    }
}
