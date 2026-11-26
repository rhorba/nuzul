package ma.nuzul.shared.outbox;

import org.springframework.context.ApplicationContext;

/**
 * Lets ITs outside this package run one relay pass, as {@link IdempotentConsumerIT} does with the package-private
 * {@link OutboxRelay}. Tests drive the relay by hand instead of enabling its schedule: a test context stays cached
 * for the whole run, and a scheduled relay would keep publishing rows other ITs assert are unpublished.
 */
public final class OutboxRelayDriver {

    private final OutboxRelay relay;

    public OutboxRelayDriver(ApplicationContext context) {
        this.relay = context.getBean(OutboxRelay.class);
    }

    /** Publishes one batch of unpublished rows, oldest first. */
    public void relayOnce() {
        relay.relay();
    }
}
