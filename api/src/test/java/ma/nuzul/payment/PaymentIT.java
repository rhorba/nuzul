package ma.nuzul.payment;

import static ma.nuzul.inventory.ReserveFixtures.inTransaction;
import static ma.nuzul.inventory.ReserveFixtures.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.ReserveFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Story 2.2: the payment table's V2 guard and the Payments API against Postgres and the fake provider. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentIT {

    private static final BigDecimal AMOUNT = new BigDecimal("700.00");

    @Autowired
    Payments payments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID reservation() {
        var fx = new ReserveFixtures(jdbc);
        UUID rt = fx.roomType("owner-" + UUID.randomUUID());
        return fx.reservation(
                rt, "guest-" + UUID.randomUUID(), "HELD", Instant.now().plusSeconds(600));
    }

    private void insertPayment(UUID reservationId, String status) {
        jdbc.update(
                "INSERT INTO payment (id, reservation_id, status, amount_mad) VALUES (?, ?, ?, 700.00)",
                UUID.randomUUID(),
                reservationId,
                status);
    }

    private Payments.Active begin(UUID reservationId) {
        return new TransactionTemplate(transactionManager).execute(s -> payments.begin(reservationId, AMOUNT));
    }

    @Test
    void theDatabaseAllowsOneActivePaymentPerReservation() {
        UUID r = reservation();
        insertPayment(r, "FAILED");
        insertPayment(r, "REFUNDED");
        insertPayment(r, "PENDING");

        assertThatThrownBy(() -> insertPayment(r, "PENDING")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPayment(r, "CAPTURED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPayment(r, "PAID")).isInstanceOf(DataIntegrityViolationException.class);
        insertPayment(r, "FAILED");
        insertPayment(r, "REFUNDING");
    }

    @Test
    void parallelBeginsForOneReservationShareOnePayment() throws Exception {
        UUID r = reservation();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Set<UUID> ids = Collections.synchronizedSet(new java.util.HashSet<>());
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() ->
                    inTransaction(tx, () -> ids.add(payments.begin(r, AMOUNT).paymentId())));
        }

        assertThat(race(tasks, 30)).containsOnlyNulls();
        assertThat(ids).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE reservation_id = ?", Long.class, r))
                .isEqualTo(1);
    }

    @Test
    void chargeCaptureAndRefundAgainstTheFakeProvider() {
        UUID r = reservation();
        Payments.Active p = begin(r);

        ChargeResult approved = payments.charge(p.paymentId(), "tok_visa_4242");
        assertThat(approved).isEqualTo(new ChargeResult.Approved("fake_ch_" + p.paymentId()));
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> payments.capture(p.paymentId(), "fake_ch_" + p.paymentId()));
        assertThat(status(p.paymentId())).isEqualTo("CAPTURED");
        assertThat(begin(r)).isEqualTo(new Payments.Active(p.paymentId(), PaymentStatus.CAPTURED, AMOUNT));

        UUID r2 = reservation();
        Payments.Active q = begin(r2);
        payments.charge(q.paymentId(), "tok_visa_4242");
        payments.refund(q.paymentId(), "fake_ch_" + q.paymentId());
        assertThat(status(q.paymentId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT provider_ref FROM payment WHERE id = ?", String.class, q.paymentId()))
                .isEqualTo("fake_ch_" + q.paymentId());
    }

    @Test
    void aDeclinedCardIsFailedAndAnotherAttemptMayStart() {
        UUID r = reservation();
        Payments.Active p = begin(r);

        assertThat(payments.charge(p.paymentId(), "tok_visa_0002")).isInstanceOf(ChargeResult.Declined.class);
        assertThat(status(p.paymentId())).isEqualTo("FAILED");
        assertThat(begin(r).paymentId()).isNotEqualTo(p.paymentId());
    }

    @Test
    void theProviderIsNeverCalledInsideATransaction() {
        UUID r = reservation();
        Payments.Active p = begin(r);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> payments.charge(p.paymentId(), "tok")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> payments.refund(p.paymentId(), "ref")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> payments.begin(r, AMOUNT)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(status(p.paymentId())).isEqualTo("PENDING");
    }

    private String status(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId);
    }
}
