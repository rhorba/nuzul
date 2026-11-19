package ma.nuzul.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.nuzul.shared.error.DomainValidationException;

/**
 * A hotel owned by one hotel admin ({@code ownerSub} = the admin's JWT subject). {@code version} is null until
 * the hotel is first persisted.
 */
public record Hotel(
        UUID id,
        String ownerSub,
        String name,
        String city,
        String address,
        Integer stars,
        List<String> amenities,
        BigDecimal lat,
        BigDecimal lon,
        HotelStatus status,
        Instant createdAt,
        Integer version) {

    private static final BigDecimal MAX_LAT = BigDecimal.valueOf(90);
    private static final BigDecimal MAX_LON = BigDecimal.valueOf(180);

    public Hotel {
        if (id == null) {
            throw new DomainValidationException("id is required");
        }
        requireText(ownerSub, "ownerSub");
        requireText(name, "name");
        requireText(city, "city");
        if (stars != null && (stars < 1 || stars > 5)) {
            throw new DomainValidationException("stars must be between 1 and 5");
        }
        requireWithin(lat, MAX_LAT, "lat");
        requireWithin(lon, MAX_LON, "lon");
        if (status == null) {
            throw new DomainValidationException("status is required");
        }
        if (amenities != null) {
            amenities.forEach(a -> requireText(a, "amenity"));
        }
        amenities = amenities == null ? List.of() : List.copyOf(amenities);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
    }

    private static void requireWithin(BigDecimal value, BigDecimal bound, String field) {
        if (value != null && value.abs().compareTo(bound) > 0) {
            throw new DomainValidationException(field + " must be between -" + bound + " and " + bound);
        }
    }
}
