package ma.nuzul.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import ma.nuzul.inventory.SoldOutException;
import ma.nuzul.inventory.StayPricing;
import ma.nuzul.inventory.StayQuote;
import ma.nuzul.inventory.domain.RoomCount;
import ma.nuzul.inventory.domain.StayRange;
import ma.nuzul.shared.error.DomainValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sums the stay's rates in one query; a stay with any unpriced night is SOLD_OUT, never a partial price. readOnly
 * only applies when called on its own: inside the hold it joins the caller's write transaction.
 */
@Service
@Transactional(readOnly = true)
class StayPricingService implements StayPricing {

    /** reservation.total_mad is NUMERIC(12,2): a larger total cannot be stored, so it is refused up front (400). */
    static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999.99");

    private final RateRepositoryPort rates;

    StayPricingService(RateRepositoryPort rates) {
        this.rates = rates;
    }

    @Override
    public StayQuote quote(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        if (roomTypeId == null) {
            throw new DomainValidationException("roomTypeId is required");
        }
        StayRange stay = new StayRange(checkIn, checkOut);
        RoomCount.requireValid(rooms);

        RateRepositoryPort.PricedNights priced = rates.priceNights(roomTypeId, stay.checkIn(), stay.checkOut());
        if (priced.nights() != stay.nights()) {
            throw new SoldOutException();
        }
        BigDecimal total = priced.totalMad().multiply(BigDecimal.valueOf(rooms));
        if (total.compareTo(MAX_TOTAL) > 0) {
            throw new DomainValidationException("the stay costs more than " + MAX_TOTAL + " MAD");
        }
        return new StayQuote(total, priced.allRefundable(), priced.maxFreeCancelHours());
    }
}
