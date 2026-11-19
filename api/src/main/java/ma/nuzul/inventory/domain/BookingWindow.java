package ma.nuzul.inventory.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import ma.nuzul.shared.error.DomainValidationException;

/**
 * Nights that can be stocked or booked: the half-open range [today, today + {@value #DAYS} days). Keeping writes
 * inside it also keeps rows out of the room_inventory DEFAULT partition (pg_partman premakes 18 months).
 *
 * @param today the current date in the hotel time zone, from the application Clock
 */
public record BookingWindow(LocalDate today) {

    public static final int DAYS = 180;
    public static final String NIGHT_OUTSIDE_WINDOW = "NIGHT_OUTSIDE_WINDOW";

    public BookingWindow {
        if (today == null) {
            throw new DomainValidationException("today is required");
        }
    }

    public LocalDate endExclusive() {
        return today.plusDays(DAYS);
    }

    public boolean contains(LocalDate night) {
        return !night.isBefore(today) && night.isBefore(endExclusive());
    }

    /** Refuses the whole list if any night is outside; the offending nights are listed ascending as ISO dates. */
    public void requireContains(Collection<LocalDate> nights) {
        List<String> outside = nights.stream()
                .filter(n -> !contains(n))
                .sorted()
                .map(LocalDate::toString)
                .toList();
        if (!outside.isEmpty()) {
            throw new DomainValidationException(
                    NIGHT_OUTSIDE_WINDOW,
                    "Nights must be from " + today + " to before " + endExclusive(),
                    Map.of("nights", outside));
        }
    }
}
