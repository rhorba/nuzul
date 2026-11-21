package ma.nuzul.payment;

/** What the payment provider answered to a charge. */
public sealed interface ChargeResult {

    /** The money was taken; {@code providerRef} identifies the charge at the provider (needed to refund it). */
    record Approved(String providerRef) implements ChargeResult {}

    /** The card was refused; nothing was taken. */
    record Declined(String reason) implements ChargeResult {}
}
