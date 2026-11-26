package ma.nuzul.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Inventory's published API for search (story 3.2): the cheapest nightly rate per calendar month. */
public interface MinPrices {

    /**
     * For each month of [month of {@code from}, that month + {@code months}), the lowest {@code price_mad} over the
     * given room types' nights on or after {@code from} in that month. Exact NUMERIC as BigDecimal, never a double.
     * A month without any rate is absent (not zero: unpriced means not for sale); no room types gives an empty map.
     *
     * @throws IllegalArgumentException when {@code months} is negative
     */
    Map<YearMonth, BigDecimal> byMonth(Collection<UUID> roomTypeIds, LocalDate from, int months);
}
