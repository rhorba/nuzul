package ma.nuzul.shared.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The relay's health, read from the table on each scrape (two cheap reads on the unpublished partial index), so every
 * replica reports it whether or not its own relay is enabled:
 *
 * <ul>
 *   <li>{@code outbox.lag.seconds}: age of the oldest event still to publish (database clock), 0 when caught up. It
 *       grows while Kafka is down or the relay is stuck.
 *   <li>{@code outbox.failed}: events parked at {@code max-attempts}; anything above 0 needs a person.
 * </ul>
 *
 * A failed read reports NaN (no data) instead of throwing into the scrape.
 */
@Component
class OutboxMetrics implements MeterBinder {

    static final String LAG = "outbox.lag.seconds";
    static final String FAILED = "outbox.failed";

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final OutboxRelayStore store;
    private final int maxAttempts;

    OutboxMetrics(OutboxRelayStore store, @Value("${app.outbox.max-attempts:10}") int maxAttempts) {
        this.store = store;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(LAG, this, OutboxMetrics::lagSeconds)
                .description("Age of the oldest unpublished outbox event")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(FAILED, this, OutboxMetrics::failed)
                .description("Outbox events parked after max-attempts failures")
                .register(registry);
    }

    double lagSeconds() {
        try {
            return store.lagSeconds(maxAttempts);
        } catch (RuntimeException e) {
            log.debug("Could not read {}", LAG, e);
            return Double.NaN;
        }
    }

    double failed() {
        try {
            return store.parkedCount(maxAttempts);
        } catch (RuntimeException e) {
            log.debug("Could not read {}", FAILED, e);
            return Double.NaN;
        }
    }
}
