package ma.nuzul.shared.outbox;

import java.util.UUID;

/**
 * Records an event in the caller's business transaction (ADR-4). The row commits or rolls back with the change it
 * describes, so an event is never published for a change that did not happen, and never lost for one that did.
 */
public interface OutboxPort {

    /**
     * @param aggregateId the Kafka message key: one aggregate's events stay on one partition, in order
     * @param type the event type, e.g. {@code ReservationHeld} (sent as the {@code event-type} header)
     * @param payload serialised to JSON; it must carry no secrets or payment data
     * @return the event id consumers dedupe on ({@code event-id} header)
     * @throws org.springframework.transaction.IllegalTransactionStateException when called outside a transaction
     */
    UUID append(Aggregate aggregate, UUID aggregateId, String type, Object payload);
}
