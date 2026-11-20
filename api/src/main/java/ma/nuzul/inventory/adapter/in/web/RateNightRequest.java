package ma.nuzul.inventory.adapter.in.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import ma.nuzul.inventory.domain.RateChange;

/**
 * One entry of the PUT body: the price (MAD, exact decimal) and refundability of one night. The bounds match
 * {@link RateChange} and price_mad NUMERIC(10,2). free_cancel_hours is deliberately not exposed.
 */
record RateNightRequest(
        @NotNull LocalDate night,

        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = RateChange.MAX_SCALE)
        BigDecimal priceMad,

        @NotNull Boolean refundable) {

    RateChange toChange() {
        return new RateChange(night, priceMad, refundable);
    }
}
