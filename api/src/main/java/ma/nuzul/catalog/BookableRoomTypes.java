package ma.nuzul.catalog;

import java.util.Optional;
import java.util.UUID;

/**
 * Catalog's published API for booking: the hotel of a room type that guests may book right now. Unknown room types and
 * room types of an INACTIVE hotel both answer empty, so a retired hotel takes no new holds.
 */
public interface BookableRoomTypes {

    Optional<UUID> hotelOf(UUID roomTypeId);
}
