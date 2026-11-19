package ma.nuzul.inventory.domain;

import java.time.LocalDate;
import ma.nuzul.shared.error.DomainValidationException;

/** The number of rooms of one room type that can be sold on one night. Never touches {@code reserved}. */
public record InventoryChange(LocalDate night, int total) {

    /** Upper bound owned by the domain; the web request (@Max) must agree with it. */
    public static final int MAX_TOTAL = 1000;

    public InventoryChange {
        if (night == null) {
            throw new DomainValidationException("night is required");
        }
        if (total < 0 || total > MAX_TOTAL) {
            throw new DomainValidationException("total must be between 0 and " + MAX_TOTAL);
        }
    }
}
