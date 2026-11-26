package ma.nuzul.catalog;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A hotel's current catalog state as other modules read it (the search indexer rebuilds its document from this).
 * Read-only and detached: it carries no owner and no version, only what a reader outside catalog may see.
 *
 * @param lat null together with {@code lon} when the hotel has no coordinates
 * @param stars 1 to 5, null when unrated
 * @param active false for an INACTIVE (retired) hotel, which the indexer removes from search
 * @param roomTypeIds the hotel's room types, ascending by id
 */
public record HotelSnapshot(
        UUID id,
        String name,
        String city,
        @Nullable Double lat,
        @Nullable Double lon,
        @Nullable Integer stars,
        List<String> amenities,
        boolean active,
        List<UUID> roomTypeIds) {

    public HotelSnapshot {
        amenities = List.copyOf(amenities);
        roomTypeIds = List.copyOf(roomTypeIds);
    }
}
