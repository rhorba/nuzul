package ma.nuzul.catalog.domain;

import java.util.UUID;
import ma.nuzul.shared.error.DomainValidationException;

/**
 * A kind of room in one hotel (e.g. "Double", sleeps 2). Refers to its hotel by id only, so the hotel and the
 * room type stay separate aggregates.
 */
public record RoomType(UUID id, UUID hotelId, String name, int capacity, String bedConfig) {

    /** Upper bound owned by the domain; the SMALLINT column and the web request (@Max) must agree with it. */
    public static final int MAX_CAPACITY = 20;

    public RoomType {
        if (id == null) {
            throw new DomainValidationException("id is required");
        }
        if (hotelId == null) {
            throw new DomainValidationException("hotelId is required");
        }
        if (name == null || name.isBlank()) {
            throw new DomainValidationException("name must not be blank");
        }
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new DomainValidationException("capacity must be between 1 and " + MAX_CAPACITY);
        }
        if (bedConfig != null && bedConfig.isBlank()) {
            bedConfig = null;
        }
    }
}
