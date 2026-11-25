package ma.nuzul.catalog;

import java.util.Optional;
import java.util.UUID;

/**
 * Catalog's published API for other modules: does this room type belong to a hotel owned by {@code ownerSub}?
 * Unknown ids and other owners' room types both answer empty / {@code false} (no existence leak).
 */
public interface RoomTypeOwnership {

    /**
     * The room type's hotel id, only when that hotel belongs to {@code ownerSub}, in one owner-scoped query: a caller
     * that needs both the ownership check and the hotel (rates key their event by hotel) never reads the hotel id
     * unchecked. Any hotel status: an INACTIVE hotel's owner still manages its rates.
     */
    Optional<UUID> hotelOfOwned(UUID roomTypeId, String ownerSub);

    default boolean isOwnedBy(UUID roomTypeId, String ownerSub) {
        return hotelOfOwned(roomTypeId, ownerSub).isPresent();
    }
}
