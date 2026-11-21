package ma.nuzul.payment.application;

import java.math.BigDecimal;
import ma.nuzul.payment.ChargeResult;

/** Out-port to the payment provider (v1: FakePaymentAdapter; a Stripe test-mode adapter is a later extension). */
public interface PaymentPort {

    /**
     * Charges {@code amountMad}. The provider must treat a repeated {@code idempotencyKey} as the same charge and
     * return its first answer.
     */
    ChargeResult charge(String idempotencyKey, BigDecimal amountMad, String paymentToken);

    /**
     * Refunds an approved charge in full; returns the provider's refund reference. The provider must treat a repeated
     * {@code idempotencyKey} as the same refund (money returned once): refunds are sent outside any transaction, so two
     * callers (concurrent cancels, a retry after a timeout) may both send the same one.
     */
    String refund(String idempotencyKey, String chargeRef, BigDecimal amountMad);
}
