package ma.nuzul.shared.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** The outbox table as the relay, its gauges and its retention job see it. All times are the database clock. */
interface OutboxRelayStore {

    /**
     * Locks up to {@code batchSize} unpublished, not-yet-parked rows, oldest first, skipping rows another run holds.
     * Must be called inside the transaction that then marks them.
     */
    List<OutboxRow> lockBatch(int maxAttempts, int batchSize);

    void markPublished(UUID id);

    /** @return the row's attempts after the increment */
    int markFailed(UUID id, String error);

    /** Age in seconds of the oldest row still to publish; 0 when there is none. */
    double lagSeconds(int maxAttempts);

    /** Rows parked after {@code maxAttempts} failures: they need a person (reset attempts to 0 to replay). */
    long parkedCount(int maxAttempts);

    /** Deletes rows published longer than {@code retention} ago; unpublished and parked rows are never touched. */
    int deletePublishedBefore(Duration retention);
}
