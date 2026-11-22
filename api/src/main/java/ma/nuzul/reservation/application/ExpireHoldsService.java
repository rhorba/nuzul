package ma.nuzul.reservation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Lists due holds (one cheap indexed read), then expires them one transaction each through {@link ExpiryTransaction}.
 * The batch size caps the work of one run, not a transaction: a backlog drains over the next runs, and one failing
 * reservation is logged and skipped instead of blocking the rest.
 */
@Service
class ExpireHoldsService implements ExpireHoldsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpireHoldsService.class);

    private final ReservationRepositoryPort reservations;
    private final ExpiryTransaction transaction;
    private final Clock clock;
    private final int batchSize;

    ExpireHoldsService(
            ReservationRepositoryPort reservations,
            ExpiryTransaction transaction,
            Clock clock,
            @Value("${app.expiry.batch-size:500}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.expiry.batch-size must be at least 1, was " + batchSize);
        }
        this.reservations = reservations;
        this.transaction = transaction;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    public int expireDueHolds() {
        Instant now = Instant.now(clock);
        int expired = 0;
        for (UUID id : reservations.expiredHoldIds(now, batchSize)) {
            try {
                if (transaction.expire(id, now)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Could not expire hold {}; the next run retries it", id, e);
            }
        }
        if (expired > 0) {
            log.info("Expired {} hold(s)", expired);
        }
        return expired;
    }
}
