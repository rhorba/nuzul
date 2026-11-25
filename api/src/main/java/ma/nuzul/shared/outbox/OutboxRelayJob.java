package ma.nuzul.shared.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Driving adapter: every {@code app.outbox.interval} (fixed delay, so runs never overlap on a replica), one replica
 * relays a batch. Here the ShedLock lock does more than save work: it keeps one relay at a time, which per-aggregate
 * order relies on (see {@link OutboxRelay}). lockAtMostFor stays short so a crashed replica's lock frees quickly; a
 * run cannot outlive it, because the relay stops starting sends once {@code app.outbox.run-budget} is spent and
 * checks at startup that the budget plus one send timeout plus a margin (metadata blocking, lock, commit) fits
 * inside it.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayJob {

    static final String LOCK_NAME = "outbox-relay";
    static final String LOCK_AT_MOST_FOR = "PT1M";

    private final OutboxRelay relay;

    OutboxRelayJob(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.outbox.interval:PT1S}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR, lockAtLeastFor = "PT0.5S")
    void run() {
        relay.relay();
    }
}
