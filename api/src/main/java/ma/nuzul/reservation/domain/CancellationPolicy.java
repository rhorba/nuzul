package ma.nuzul.reservation.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import ma.nuzul.shared.error.DomainValidationException;

/**
 * A reservation's cancellation terms, frozen at hold time from the stay's rates (a later rate edit never changes
 * them). Cancelling is free, with a full refund, only while the stay is refundable and before {@code freeUntil}.
 *
 * @param refundable every night of the stay was refundable
 * @param freeUntil the free-cancellation deadline (exclusive); null when the stay is not refundable
 */
public record CancellationPolicy(boolean refundable, Instant freeUntil) {

    public static final CancellationPolicy NON_REFUNDABLE = new CancellationPolicy(false, null);

    public CancellationPolicy {
        if (refundable && freeUntil == null) {
            throw new DomainValidationException("a refundable stay needs a free-cancellation deadline");
        }
        if (!refundable) {
            freeUntil = null;
        }
    }

    /**
     * The deadline is the check-in day at 00:00 in the hotel time zone minus the stay's strictest
     * {@code free_cancel_hours}: with 48 h and a check-in on 10 Dec, cancelling is free until 8 Dec 00:00.
     */
    public static CancellationPolicy forStay(boolean refundable, int freeCancelHours, LocalDate checkIn, ZoneId zone) {
        if (!refundable) {
            return NON_REFUNDABLE;
        }
        if (freeCancelHours < 0) {
            throw new DomainValidationException("free_cancel_hours must not be negative");
        }
        Instant checkInStart = checkIn.atStartOfDay(zone).toInstant();
        return new CancellationPolicy(true, checkInStart.minus(Duration.ofHours(freeCancelHours)));
    }

    /** Would cancelling at {@code now} be free (full refund)? */
    public boolean isFreeAt(Instant now) {
        return refundable && now.isBefore(freeUntil);
    }
}
