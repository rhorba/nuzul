package ma.nuzul.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.nuzul.catalog.RoomTypeChanged;
import ma.nuzul.catalog.domain.RoomType;
import ma.nuzul.shared.error.NotFoundException;
import ma.nuzul.shared.outbox.Aggregate;
import ma.nuzul.shared.outbox.OutboxPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every operation first loads the parent hotel through the owner-scoped finder (HOTEL_NOT_FOUND otherwise), then
 * reads room types through finders that are owner-scoped again in SQL. Every write appends a {@code RoomTypeChanged}
 * event in the same transaction.
 */
@Service
@Transactional
class RoomTypeAdminService implements ManageRoomTypesUseCase {

    static final String ROOM_TYPE_NOT_FOUND = "ROOM_TYPE_NOT_FOUND";

    private final HotelRepositoryPort hotels;
    private final RoomTypeRepositoryPort roomTypes;
    private final OutboxPort outbox;
    private final Clock clock;

    RoomTypeAdminService(HotelRepositoryPort hotels, RoomTypeRepositoryPort roomTypes, OutboxPort outbox, Clock clock) {
        this.hotels = hotels;
        this.roomTypes = roomTypes;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public RoomType create(String ownerSub, UUID hotelId, RoomTypeDetails d) {
        requireOwnHotel(ownerSub, hotelId);
        return changed(roomTypes.save(new RoomType(UUID.randomUUID(), hotelId, d.name(), d.capacity(), d.bedConfig())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomType> list(String ownerSub, UUID hotelId) {
        requireOwnHotel(ownerSub, hotelId);
        return roomTypes.findAllByHotelAndOwner(hotelId, ownerSub);
    }

    @Override
    public RoomType update(String ownerSub, UUID hotelId, UUID roomTypeId, RoomTypeDetails d) {
        requireOwnHotel(ownerSub, hotelId);
        RoomType current = roomTypes
                .findByIdAndHotelAndOwner(roomTypeId, hotelId, ownerSub)
                .orElseThrow(
                        () -> new NotFoundException(ROOM_TYPE_NOT_FOUND, "Room type " + roomTypeId + " not found"));
        return changed(
                roomTypes.save(new RoomType(current.id(), current.hotelId(), d.name(), d.capacity(), d.bedConfig())));
    }

    /** Appends the event for a room type that was just saved, and hands it back. */
    private RoomType changed(RoomType saved) {
        outbox.append(
                Aggregate.ROOM_TYPE,
                saved.id(),
                RoomTypeChanged.TYPE,
                new RoomTypeChanged(saved.id(), saved.hotelId(), Instant.now(clock)));
        return saved;
    }

    private void requireOwnHotel(String ownerSub, UUID hotelId) {
        if (hotels.findByIdAndOwner(hotelId, ownerSub).isEmpty()) {
            throw new NotFoundException(HotelAdminService.HOTEL_NOT_FOUND, "Hotel " + hotelId + " not found");
        }
    }
}
