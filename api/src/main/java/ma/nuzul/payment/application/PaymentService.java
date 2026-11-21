package ma.nuzul.payment.application;

import java.math.BigDecimal;
import java.util.UUID;
import ma.nuzul.payment.ChargeResult;
import ma.nuzul.payment.PaymentStatus;
import ma.nuzul.payment.Payments;
import ma.nuzul.payment.application.PaymentRepositoryPort.PaymentRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Payment rows plus the provider call. Rows written outside a transaction ({@link #charge} marking FAILED,
 * {@link #refund} marking REFUNDED) are single conditional statements in autocommit, so a crash leaves a consistent
 * row: still PENDING, which the next confirm resumes with the same provider idempotency key.
 */
@Service
class PaymentService implements Payments {

    private final PaymentRepositoryPort payments;
    private final PaymentPort provider;

    PaymentService(PaymentRepositoryPort payments, PaymentPort provider) {
        this.payments = payments;
        this.provider = provider;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Active begin(UUID reservationId, BigDecimal amountMad) {
        if (reservationId == null || amountMad == null || amountMad.signum() <= 0) {
            throw new IllegalArgumentException("reservationId and a positive amount are required");
        }
        payments.insertPendingIfNoneActive(UUID.randomUUID(), reservationId, amountMad);
        // Inserted or not, READ COMMITTED lets this new statement see the active row (ours, or the committed winner's).
        PaymentRow active = payments.findActive(reservationId)
                .orElseThrow(() -> new IllegalStateException("no active payment after insert"));
        return new Active(active.id(), active.status(), active.amountMad());
    }

    @Override
    public ChargeResult charge(UUID paymentId, String paymentToken) {
        requireNoTransaction("charge");
        PaymentRow payment = find(paymentId);
        /* Two confirms sharing this payment: whichever comes second gets the first one's answer, never a new charge. */
        switch (payment.status()) {
            case CAPTURED, REFUNDING, REFUNDED -> {
                return new ChargeResult.Approved(payment.providerRef());
            }
            case FAILED -> {
                return new ChargeResult.Declined("card declined");
            }
            case PENDING -> {
                // charged below
            }
        }
        ChargeResult result = provider.charge(paymentId.toString(), payment.amountMad(), paymentToken);
        boolean recorded = switch (result) {
            case ChargeResult.Approved approved ->
                payments.transition(paymentId, PaymentStatus.PENDING, PaymentStatus.PENDING, approved.providerRef());
            case ChargeResult.Declined declined ->
                payments.transition(paymentId, PaymentStatus.PENDING, PaymentStatus.FAILED, null);
        };
        // A concurrent attempt on this payment settled it first: its recorded outcome is the answer.
        return recorded ? result : fromState(find(paymentId));
    }

    private static ChargeResult fromState(PaymentRow payment) {
        return switch (payment.status()) {
            case FAILED -> new ChargeResult.Declined("card declined");
            case PENDING, CAPTURED, REFUNDING, REFUNDED -> new ChargeResult.Approved(payment.providerRef());
        };
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean capture(UUID paymentId, String providerRef) {
        return payments.transition(paymentId, PaymentStatus.PENDING, PaymentStatus.CAPTURED, providerRef);
    }

    /*
     * Claim first, then call the provider: the conditional PENDING -> REFUNDING update and capture's PENDING -> CAPTURED
     * update lock the same row, so exactly one of them wins and a captured payment is never refunded. REFUNDED is only
     * written after the provider confirmed: a provider error (or a crash) leaves REFUNDING, never a REFUNDED row whose
     * money was not returned. A REFUNDING payment is refunded again on the next call; the provider deduplicates by the
     * charge reference.
     */
    @Override
    public boolean refund(UUID paymentId, String providerRef) {
        requireNoTransaction("refund");
        PaymentRow payment = find(paymentId);
        switch (payment.status()) {
            case REFUNDED -> {
                return true;
            }
            case PENDING -> {
                if (!payments.transition(paymentId, PaymentStatus.PENDING, PaymentStatus.REFUNDING, null)) {
                    return refund(paymentId, providerRef); // lost the claim race: decide again on the new state
                }
            }
            case REFUNDING -> {
                // claimed earlier (a provider error or a concurrent refund): send it again
            }
            case CAPTURED, FAILED -> {
                return false;
            }
        }
        provider.refund(providerRef, payment.amountMad());
        // provider_ref keeps the charge reference: a later charge() of this payment answers with it.
        payments.transition(paymentId, PaymentStatus.REFUNDING, PaymentStatus.REFUNDED, null);
        return true;
    }

    private PaymentRow find(UUID paymentId) {
        return payments.find(paymentId)
                .orElseThrow(() -> new IllegalStateException("payment " + paymentId + " not found"));
    }

    private static void requireNoTransaction(String operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " calls the payment provider: never inside a transaction");
        }
    }
}
