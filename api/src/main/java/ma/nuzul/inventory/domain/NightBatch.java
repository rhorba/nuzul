package ma.nuzul.inventory.domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import ma.nuzul.shared.error.DomainValidationException;

/**
 * The shape every per-night admin batch (inventory totals, rates) must have: 1..{@value #MAX_SIZE} non-null entries
 * and no night twice. The booking window is checked separately by {@link BookingWindow}, after ownership.
 */
public final class NightBatch {

    /** A batch never needs more entries than the window has nights. */
    public static final int MAX_SIZE = BookingWindow.DAYS;

    private NightBatch() {}

    /**
     * @param night reads the night of one (non-null) entry
     * @return the nights, in request order
     * @throws DomainValidationException VALIDATION_FAILED when the batch is null, empty, too long, has a null entry
     *     or repeats a night
     */
    public static <T> List<LocalDate> requireValid(List<T> entries, Function<? super T, LocalDate> night) {
        if (entries == null || entries.isEmpty()) {
            throw new DomainValidationException("at least one night is required");
        }
        if (entries.size() > MAX_SIZE) {
            throw new DomainValidationException("at most " + MAX_SIZE + " nights per request");
        }
        Set<LocalDate> seen = new HashSet<>();
        for (T entry : entries) {
            if (entry == null) {
                throw new DomainValidationException("entries must not be null");
            }
            if (!seen.add(night.apply(entry))) {
                throw new DomainValidationException("duplicate night " + night.apply(entry));
            }
        }
        return entries.stream().map(night).toList();
    }
}
