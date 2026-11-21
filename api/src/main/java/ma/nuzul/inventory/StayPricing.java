package ma.nuzul.inventory;

import java.time.LocalDate;
import java.util.UUID;

/** Inventory's published API for quoting a stay from the {@code rate} rows (price and cancellation terms) at hold time. */
public interface StayPricing {

    /**
     * The exact price of {@code rooms} rooms over the half-open stay [checkIn, checkOut) (the sum of each night's
     * {@code price_mad}, times {@code rooms}, computed in SQL as NUMERIC, never a double) and its cancellation terms.
     *
     * @throws SoldOutException SOLD_OUT when a night of the stay has no rate: a night without a price is not for sale
     * @throws ma.nuzul.shared.error.DomainValidationException VALIDATION_FAILED for an invalid stay or room count
     */
    StayQuote quote(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms);
}
