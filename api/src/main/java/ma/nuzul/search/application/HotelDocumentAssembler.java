package ma.nuzul.search.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.TreeMap;
import ma.nuzul.catalog.HotelSnapshot;
import ma.nuzul.inventory.MinPrices;
import org.springframework.stereotype.Component;

/**
 * Builds one hotel's search document (story 3.2), for the live indexer and the full reindex alike, so both always
 * write the same shape. Prices cover {@value #MONTHS} months from today, "today" taken from the shared Clock (its zone
 * decides the date, not the JVM's).
 */
@Component
public class HotelDocumentAssembler {

    /** Months of cheapest rates indexed, starting with the current month (the booking window is 180 days). */
    static final int MONTHS = 12;

    private final MinPrices minPrices;
    private final Clock clock;

    public HotelDocumentAssembler(MinPrices minPrices, Clock clock) {
        this.minPrices = minPrices;
        this.clock = clock;
    }

    /**
     * @throws IllegalArgumentException for an inactive hotel: a retired hotel is deleted from search, never indexed
     */
    public HotelDocument assemble(HotelSnapshot hotel) {
        if (!hotel.active()) {
            throw new IllegalArgumentException("hotel " + hotel.id() + " is inactive and must not be indexed");
        }
        Map<YearMonth, BigDecimal> byMonth = minPrices.byMonth(hotel.roomTypeIds(), LocalDate.now(clock), MONTHS);
        var prices = new TreeMap<String, BigDecimal>();
        // YearMonth.toString is yyyy-MM for years 0000-9999, which is all the clock can produce here.
        byMonth.forEach((month, price) -> prices.put(month.toString(), price));
        return new HotelDocument(
                hotel.id(),
                hotel.name(),
                hotel.city(),
                hotel.lat(),
                hotel.lon(),
                hotel.stars(),
                hotel.amenities(),
                prices);
    }
}
