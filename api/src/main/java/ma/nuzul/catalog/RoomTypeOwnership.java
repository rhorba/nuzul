package ma.nuzul.catalog;

import java.util.UUID;

/**
 * Catalog's published API for other modules: does this room type belong to a hotel owned by {@code ownerSub}?
 * Unknown ids and other owners' room types both answer {@code false} (no existence leak).
 */
public interface RoomTypeOwnership {

    boolean isOwnedBy(UUID roomTypeId, String ownerSub);
}
