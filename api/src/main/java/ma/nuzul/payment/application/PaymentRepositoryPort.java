package ma.nuzul.payment.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.payment.PaymentStatus;

/** Out-port for payment rows. Every status change is conditional on the status it leaves. */
public interface PaymentRepositoryPort {

    record PaymentRow(UUID id, UUID reservationId, PaymentStatus status, BigDecimal amountMad, String providerRef) {}

    /**
     * Inserts a PENDING payment unless the reservation already has an active one (payment_one_active). A concurrent
     * insert for the same reservation waits for the other transaction: false once it committed.
     */
    boolean insertPendingIfNoneActive(UUID id, UUID reservationId, BigDecimal amountMad);

    Optional<PaymentRow> findActive(UUID reservationId);

    Optional<PaymentRow> find(UUID id);

    /** @return true when the payment moved {@code from} → {@code to} (it was in {@code from}) */
    boolean transition(UUID id, PaymentStatus from, PaymentStatus to, String providerRef);
}
