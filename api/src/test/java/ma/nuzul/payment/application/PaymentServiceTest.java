package ma.nuzul.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.payment.ChargeResult;
import ma.nuzul.payment.PaymentStatus;
import ma.nuzul.payment.Payments;
import org.junit.jupiter.api.Test;

/** Payment rules against an in-memory table and a scripted provider; no Spring, no transactions. */
class PaymentServiceTest {

    private static final BigDecimal AMOUNT = new BigDecimal("2100.00");

    private final InMemoryPayments rows = new InMemoryPayments();
    private final List<String> providerCalls = new ArrayList<>();
    private ChargeResult nextAnswer = new ChargeResult.Approved("ch_1");
    private boolean failRefund;

    private final PaymentPort provider = new PaymentPort() {
        @Override
        public ChargeResult charge(String idempotencyKey, BigDecimal amountMad, String paymentToken) {
            providerCalls.add("charge " + idempotencyKey + " " + amountMad + " " + paymentToken);
            return nextAnswer;
        }

        @Override
        public String refund(String chargeRef, BigDecimal amountMad) {
            providerCalls.add("refund " + chargeRef + " " + amountMad);
            if (failRefund) {
                throw new IllegalStateException("provider timeout");
            }
            return "re_1";
        }
    };

    private final PaymentService service = new PaymentService(rows, provider);
    private final UUID reservationId = UUID.randomUUID();

    @Test
    void beginStartsOnePendingPaymentAndReusesIt() {
        Payments.Active first = service.begin(reservationId, AMOUNT);
        Payments.Active again = service.begin(reservationId, AMOUNT);

        assertThat(first.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(first.amountMad()).isEqualTo(AMOUNT);
        assertThat(again).isEqualTo(first);
        assertThat(rows.byId).hasSize(1);
    }

    @Test
    void chargeUsesThePaymentIdAsIdempotencyKeyAndTheStoredAmount() {
        Payments.Active p = service.begin(reservationId, AMOUNT);

        assertThat(service.charge(p.paymentId(), "tok_4242")).isEqualTo(new ChargeResult.Approved("ch_1"));
        assertThat(providerCalls).containsExactly("charge " + p.paymentId() + " 2100.00 tok_4242");
        assertThat(rows.byId.get(p.paymentId()).status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(rows.byId.get(p.paymentId()).providerRef())
                .as("recorded for a concurrent confirm")
                .isEqualTo("ch_1");
    }

    @Test
    void aDeclineMarksThePaymentFailedAndFreesTheReservationForAnotherAttempt() {
        Payments.Active p = service.begin(reservationId, AMOUNT);
        nextAnswer = new ChargeResult.Declined("card declined");

        assertThat(service.charge(p.paymentId(), "tok_0002")).isInstanceOf(ChargeResult.Declined.class);
        assertThat(rows.byId.get(p.paymentId()).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(service.begin(reservationId, AMOUNT).paymentId()).isNotEqualTo(p.paymentId());
    }

    @Test
    void captureAndRefundMovePendingPayments() {
        Payments.Active p = service.begin(reservationId, AMOUNT);
        service.capture(p.paymentId(), "ch_1");
        assertThat(rows.byId.get(p.paymentId()).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(rows.byId.get(p.paymentId()).providerRef()).isEqualTo("ch_1");
        assertThat(service.capture(p.paymentId(), "ch_1")).isFalse();

        UUID other = UUID.randomUUID();
        Payments.Active q = service.begin(other, AMOUNT);
        service.refund(q.paymentId(), "ch_2");
        assertThat(rows.byId.get(q.paymentId()).status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(providerCalls).containsExactly("refund ch_2 2100.00");
    }

    @Test
    void aSettledPaymentAnswersFromItsStateWithoutCallingTheProvider() {
        Payments.Active p = service.begin(reservationId, AMOUNT);
        service.charge(p.paymentId(), "tok");
        service.capture(p.paymentId(), "ch_1");
        providerCalls.clear();

        assertThat(service.charge(p.paymentId(), "tok")).isEqualTo(new ChargeResult.Approved("ch_1"));
        assertThat(service.refund(p.paymentId(), "ch_1"))
                .as("never refund a captured payment")
                .isFalse();
        assertThat(providerCalls).isEmpty();

        UUID other = UUID.randomUUID();
        Payments.Active q = service.begin(other, AMOUNT);
        nextAnswer = new ChargeResult.Declined("card declined");
        service.charge(q.paymentId(), "tok_0002");
        providerCalls.clear();
        assertThat(service.charge(q.paymentId(), "tok_0002")).isInstanceOf(ChargeResult.Declined.class);
        assertThat(providerCalls).isEmpty();
    }

    @Test
    void refundIsIdempotentAndKeepsTheChargeReference() {
        Payments.Active p = service.begin(reservationId, AMOUNT);
        service.charge(p.paymentId(), "tok");

        assertThat(service.refund(p.paymentId(), "ch_1")).isTrue();
        assertThat(service.refund(p.paymentId(), "ch_1")).isTrue();
        assertThat(service.capture(p.paymentId(), "ch_1"))
                .as("a refund claim beats a later capture")
                .isFalse();

        assertThat(rows.byId.get(p.paymentId()).status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(rows.byId.get(p.paymentId()).providerRef()).isEqualTo("ch_1");
        assertThat(service.refund(service.begin(UUID.randomUUID(), AMOUNT).paymentId(), "x"))
                .isTrue();
        assertThat(providerCalls).filteredOn(c -> c.startsWith("refund ch_1")).hasSize(1);
        assertThat(service.charge(p.paymentId(), "tok")).isEqualTo(new ChargeResult.Approved("ch_1"));
    }

    @Test
    void anOutcomeRecordedByAConcurrentAttemptWins() {
        Payments.Active p = service.begin(reservationId, AMOUNT);
        // The other attempt (another card) was declined while the provider was approving this one.
        rows.beforeTransition = () -> rows.transition(p.paymentId(), PaymentStatus.PENDING, PaymentStatus.FAILED, null);

        assertThat(service.charge(p.paymentId(), "tok_4242")).isInstanceOf(ChargeResult.Declined.class);
    }

    @Test
    void aProviderErrorDuringARefundLeavesItRefundingAndTheNextCallFinishesIt() {
        Payments.Active p = service.begin(reservationId, AMOUNT);
        service.charge(p.paymentId(), "tok");
        failRefund = true;

        assertThatThrownBy(() -> service.refund(p.paymentId(), "ch_1")).isInstanceOf(IllegalStateException.class);
        assertThat(rows.byId.get(p.paymentId()).status()).isEqualTo(PaymentStatus.REFUNDING);
        assertThat(service.capture(p.paymentId(), "ch_1"))
                .as("a claimed refund still blocks capture")
                .isFalse();

        failRefund = false;
        assertThat(service.refund(p.paymentId(), "ch_1")).isTrue();
        assertThat(rows.byId.get(p.paymentId()).status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(providerCalls).filteredOn(c -> c.startsWith("refund")).hasSize(2);
    }

    @Test
    void anUnknownPaymentCannotBeCharged() {
        assertThatThrownBy(() -> service.charge(UUID.randomUUID(), "tok")).isInstanceOf(IllegalStateException.class);
        assertThat(providerCalls).isEmpty();
    }

    @Test
    void beginRefusesBadArguments() {
        assertThatThrownBy(() -> service.begin(null, AMOUNT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.begin(reservationId, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.begin(reservationId, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Mirrors the SQL: at most one PENDING/CAPTURED row per reservation; transitions are conditional. */
    private static final class InMemoryPayments implements PaymentRepositoryPort {

        final Map<UUID, PaymentRow> byId = new HashMap<>();
        Runnable beforeTransition;

        @Override
        public boolean insertPendingIfNoneActive(UUID id, UUID reservationId, BigDecimal amountMad) {
            if (findActive(reservationId).isPresent()) {
                return false;
            }
            byId.put(id, new PaymentRow(id, reservationId, PaymentStatus.PENDING, amountMad, null));
            return true;
        }

        @Override
        public Optional<PaymentRow> findActive(UUID reservationId) {
            return byId.values().stream()
                    .filter(p -> p.reservationId().equals(reservationId))
                    .filter(p -> p.status() == PaymentStatus.PENDING || p.status() == PaymentStatus.CAPTURED)
                    .findFirst();
        }

        @Override
        public Optional<PaymentRow> find(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public boolean transition(UUID id, PaymentStatus from, PaymentStatus to, String providerRef) {
            if (beforeTransition != null) {
                Runnable once = beforeTransition;
                beforeTransition = null;
                once.run();
            }
            PaymentRow p = byId.get(id);
            if (p == null || p.status() != from) {
                return false;
            }
            byId.put(
                    id,
                    new PaymentRow(
                            id,
                            p.reservationId(),
                            to,
                            p.amountMad(),
                            providerRef != null ? providerRef : p.providerRef()));
            return true;
        }
    }
}
