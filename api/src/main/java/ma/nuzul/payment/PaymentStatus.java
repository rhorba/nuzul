package ma.nuzul.payment;

/**
 * Matches the payment_status CHECK (V2). PENDING and CAPTURED are "active": at most one per reservation. REFUNDING is
 * a refund claimed but not yet confirmed by the provider.
 */
public enum PaymentStatus {
    PENDING,
    CAPTURED,
    FAILED,
    REFUNDING,
    REFUNDED
}
