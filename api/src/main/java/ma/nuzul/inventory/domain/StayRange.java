package ma.nuzul.inventory.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import ma.nuzul.shared.error.DomainValidationException;

/**
 * The nights of one stay: the half-open range [checkIn, checkOut), so the checkout day is not a night stayed (database
 * doc §3 rule 2). One room_inventory row per night; at most {@value #MAX_NIGHTS} nights.
 */
public record StayRange(LocalDate checkIn, LocalDate checkOut) {

    public static final int MAX_NIGHTS = 30;

    public StayRange {
        if (checkIn == null || checkOut == null) {
            throw new DomainValidationException("checkIn and checkOut are required");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new DomainValidationException("checkOut must be after checkIn");
        }
        if (ChronoUnit.DAYS.between(checkIn, checkOut) > MAX_NIGHTS) {
            throw new DomainValidationException("a stay is at most " + MAX_NIGHTS + " nights");
        }
    }

    /** The number of nights stayed (checkOut - checkIn), 1..{@value #MAX_NIGHTS}. */
    public int nights() {
        return (int) ChronoUnit.DAYS.between(checkIn, checkOut);
    }
}
