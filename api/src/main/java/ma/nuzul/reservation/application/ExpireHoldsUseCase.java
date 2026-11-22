package ma.nuzul.reservation.application;

/**
 * In-port: give back the rooms of holds nobody paid for in time. Every HELD reservation whose hold ended becomes
 * EXPIRED and its rooms are released in the same transaction (database doc section 3, rule 4). Safe to run on several
 * replicas at once: each hold is expired, and its rooms released, exactly once.
 */
public interface ExpireHoldsUseCase {

    /** @return how many holds this run expired (at most {@code app.expiry.batch-size}) */
    int expireDueHolds();
}
