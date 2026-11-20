package ma.nuzul.inventory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import ma.nuzul.shared.error.DomainValidationException;

/**
 * The price (MAD) and refundability of one room type on one night. Money is an exact BigDecimal end to end, never a
 * double (database doc §3 rule 3). free_cancel_hours is not part of it: inserts take the column default, updates
 * leave it as it is.
 */
public record RateChange(LocalDate night, BigDecimal priceMad, boolean refundable) {

    /** price_mad is NUMERIC(10,2): at most 8 integer digits and 2 decimals. The web request (@Digits) must agree. */
    public static final int MAX_SCALE = 2;

    public static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    public RateChange {
        if (night == null) {
            throw new DomainValidationException("night is required");
        }
        if (priceMad == null) {
            throw new DomainValidationException("priceMad is required");
        }
        if (priceMad.signum() <= 0 || priceMad.compareTo(MAX_PRICE) > 0) {
            throw new DomainValidationException("priceMad must be between 0.01 and " + MAX_PRICE);
        }
        /* Trailing zeros are not precision here (10.000 is 10.00); 10.005 is refused, never rounded. The web layer is
         * stricter: @Digits counts trailing zeros on a BigDecimal, so a JSON 850.000 is already a 400 there. */
        if (priceMad.stripTrailingZeros().scale() > MAX_SCALE) {
            throw new DomainValidationException("priceMad must have at most " + MAX_SCALE + " decimals");
        }
    }
}
