package ma.nuzul.shared.outbox;

import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly (03:30 by default, off-peak), one replica deletes rows published more than {@code app.outbox.retention}
 * ago. Published rows are kept only for replay and debugging; unpublished and parked rows are never deleted, since
 * that would lose an event. The cut-off uses the database clock, like {@code published_at}.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRetentionJob {

    static final String LOCK_NAME = "outbox-retention";

    private static final Logger log = LoggerFactory.getLogger(OutboxRetentionJob.class);

    private final OutboxRelayStore store;
    private final Duration retention;

    OutboxRetentionJob(OutboxRelayStore store, @Value("${app.outbox.retention:P7D}") Duration retention) {
        if (retention.isNegative()) {
            throw new IllegalArgumentException("app.outbox.retention must not be negative, was " + retention);
        }
        this.store = store;
        this.retention = retention;
    }

    @Scheduled(cron = "${app.outbox.retention-cron:0 30 3 * * *}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void run() {
        deleteExpired();
    }

    int deleteExpired() {
        int deleted = store.deletePublishedBefore(retention);
        if (deleted > 0) {
            log.info("Deleted {} outbox event(s) published more than {} ago", deleted, retention);
        }
        return deleted;
    }
}
