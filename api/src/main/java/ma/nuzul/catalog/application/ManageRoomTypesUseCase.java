package ma.nuzul.catalog.application;

import java.util.List;
import java.util.UUID;
import ma.nuzul.catalog.domain.RoomType;
import org.springframework.modulith.NamedInterface;

/**
 * In-port: a hotel admin manages the room types of their own hotels. A hotel owned by someone else behaves like
 * one that does not exist (HOTEL_NOT_FOUND); a room type outside the given hotel is ROOM_TYPE_NOT_FOUND.
 */
@NamedInterface("admin")
public interface ManageRoomTypesUseCase {

    RoomType create(String ownerSub, UUID hotelId, RoomTypeDetails details);

    List<RoomType> list(String ownerSub, UUID hotelId);

    RoomType update(String ownerSub, UUID hotelId, UUID roomTypeId, RoomTypeDetails details);
}
