package ma.nuzul.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Inventory's published API for pricing a stay from the {@code rate} rows, at the moment of the hold. */
public interface StayPricing {

    /**
     * The exact price of {@code rooms} rooms over the half-open stay [checkIn, checkOut): the sum of each night's
     * {@code price_mad}, times {@code rooms}. Computed in SQL as NUMERIC, never a double.
     *
     * @throws SoldOutException SOLD_OUT when a night of the stay has no rate: a night without a price is not for sale
     * @throws ma.nuzul.shared.error.DomainValidationException VALIDATION_FAILED for an invalid stay or room count
     */
    BigDecimal price(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms);
}
