package ma.nuzul.shared.outbox;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Runs a consumer's handler at most once per event id, against {@code processed_event (consumer, event_id)}. The relay
 * delivers at least once (a crash between the broker's ack and its commit resends), and the listener retries a failed
 * record, so every consumer sees duplicates and must absorb them here.
 *
 * <p>The marker row and the handler share one database transaction: the insert claims the event, the handler runs, and
 * both commit together. If the handler throws, both roll back (the event is unclaimed again) and the exception reaches
 * the listener container, which retries the record and finally sends it to its dead-letter topic.
 *
 * <p>So dedup is exact only for side effects inside that same transaction (database writes). An external side effect,
 * such as an email, is at-least-once: a crash after the email leaves but before the commit sends it again on the next
 * delivery. Handlers with external effects should do them last and tolerate an occasional repeat.
 *
 * <p>Concurrent deliveries of one event (two replicas during a rebalance) serialise on the primary key: the second
 * insert waits for the first transaction, then sees the conflict, so the handler still runs once.
 */
@Component
public class IdempotentConsumer {

    static final String CLAIM = """
            INSERT INTO processed_event (consumer, event_id)
            VALUES (:consumer, :eventId)
            ON CONFLICT DO NOTHING
            """;

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final JdbcClient jdbc;
    private final TransactionOperations transaction;

    IdempotentConsumer(JdbcClient jdbc, TransactionOperations transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /**
     * @param consumer the consumer's stable name, its dedup namespace (usually its module), e.g. {@code notification}
     * @param eventId the {@code event-id} header ({@link EventHeaders#eventId})
     * @param handler runs inside the claiming transaction; whatever it throws rolls the claim back and propagates
     * @return true when the handler ran, false for a duplicate (handler skipped)
     */
    public boolean handleOnce(String consumer, UUID eventId, Runnable handler) {
        Boolean handled = transaction.execute(status -> {
            int claimed = jdbc.sql(CLAIM)
                    .param("consumer", consumer)
                    .param("eventId", eventId)
                    .update();
            if (claimed == 0) {
                log.debug("Skipping event {} already processed by {}", eventId, consumer);
                return false;
            }
            handler.run();
            return true;
        });
        return Boolean.TRUE.equals(handled);
    }
}
