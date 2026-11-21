package ma.nuzul.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payment module's published API, used by confirm (story 2.2). The provider call never runs inside a database
 * transaction (system design: no external call inside a transaction), so the API is split around it:
 * {@link #begin} and {@link #capture} join the caller's transaction, {@link #charge} and {@link #refund} must be called
 * outside one.
 */
public interface Payments {

    /** The reservation's active payment: the one just started, or the one an earlier attempt left. */
    record Active(UUID paymentId, PaymentStatus status, BigDecimal amountMad) {}

    /**
     * Returns the reservation's active payment (PENDING or CAPTURED), inserting a PENDING one for {@code amountMad}
     * when there is none. Concurrent calls for one reservation get the same payment (a unique index serializes them),
     * so they share one provider idempotency key and can never charge twice. Propagation MANDATORY.
     */
    Active begin(UUID reservationId, BigDecimal amountMad);

    /**
     * Charges a PENDING payment through the provider, with the payment id as the provider's idempotency key: charging
     * the same payment again returns the first answer instead of taking the money twice. A decline marks the payment
     * FAILED. Must not run inside a transaction.
     *
     * @throws IllegalStateException when called inside a transaction
     */
    ChargeResult charge(UUID paymentId, String paymentToken);

    /**
     * Marks a charged PENDING payment CAPTURED. Propagation MANDATORY: it commits with the reservation's CONFIRMED.
     *
     * @return false when the payment is no longer PENDING (a concurrent refund claimed it): the caller must roll back
     */
    boolean capture(UUID paymentId, String providerRef);

    /**
     * Gives an approved charge back (the hold expired while it was being paid): claims the payment PENDING ->
     * REFUNDING, calls the provider, then marks it REFUNDED. Must not run inside a transaction. A provider error
     * propagates and leaves the payment REFUNDING (retried by the next call).
     *
     * @return true when the payment is (now) REFUNDED; false when a concurrent confirm captured it first, in which case
     *     nothing was refunded and the reservation is CONFIRMED
     */
    boolean refund(UUID paymentId, String providerRef);
}
