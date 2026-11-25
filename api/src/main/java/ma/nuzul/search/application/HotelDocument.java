package ma.nuzul.search.application;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One hotel as the search index sees it (story 3.2): a projection rebuilt from Postgres, never authoritative. Prices
 * are the cheapest room-type rate per calendar month, so a "max price in December" filter is one range query.
 *
 * @param lat latitude, null together with {@code lon} when the hotel has no coordinates (no location is indexed)
 * @param stars 1 to 5, null when unrated
 * @param minPriceByMonth keyed {@code yyyy-MM}; any other key would be mapped by Elasticsearch as a guessed type
 */
public record HotelDocument(
        UUID id,
        String name,
        String city,
        @Nullable Double lat,
        @Nullable Double lon,
        @Nullable Integer stars,
        List<String> amenities,
        Map<String, BigDecimal> minPriceByMonth) {

    public HotelDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(city, "city");
        if ((lat == null) != (lon == null)) {
            throw new IllegalArgumentException("lat and lon must both be set or both be null");
        }
        amenities = List.copyOf(amenities);
        minPriceByMonth = Map.copyOf(minPriceByMonth);
        minPriceByMonth.keySet().forEach(HotelDocument::requireMonthKey);
    }

    /** Strict yyyy-MM (YearMonth.parse alone would also accept a signed or 5-digit year). */
    private static void requireMonthKey(String key) {
        try {
            if (key.length() == 7) {
                YearMonth.parse(key);
                return;
            }
        } catch (DateTimeParseException e) {
            // fall through to the error below
        }
        throw new IllegalArgumentException("minPriceByMonth key must be yyyy-MM, was " + key);
    }
}
