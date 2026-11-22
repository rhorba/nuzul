package ma.nuzul.reservation.adapter.in.scheduling;

import ma.nuzul.reservation.application.ExpireHoldsUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Driving adapter: every {@code app.expiry.interval} (fixed delay, so runs never pile up), one replica expires due
 * holds. The ShedLock lock only saves duplicate work; correctness comes from the per-reservation conditional update.
 */
@Component
@ConditionalOnProperty(name = "app.expiry.enabled", havingValue = "true", matchIfMissing = true)
class HoldExpiryJob {

    static final String LOCK_NAME = "hold-expiry";

    private final ExpireHoldsUseCase expireHolds;

    HoldExpiryJob(ExpireHoldsUseCase expireHolds) {
        this.expireHolds = expireHolds;
    }

    @Scheduled(fixedDelayString = "${app.expiry.interval:PT30S}", initialDelayString = "${app.expiry.interval:PT30S}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    void run() {
        expireHolds.expireDueHolds();
    }
}
