package ma.nuzul.catalog.application;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.catalog.domain.RoomType;

/**
 * Test fake for {@link RoomTypeRepositoryPort}. Like the real query, every lookup is scoped by hotel AND by that
 * hotel's owner (resolved through the hotel fake), not by hotel id alone.
 */
class InMemoryRoomTypeRepository implements RoomTypeRepositoryPort {

    final Map<UUID, RoomType> rows = new LinkedHashMap<>();
    private final InMemoryHotelRepository hotels;

    InMemoryRoomTypeRepository(InMemoryHotelRepository hotels) {
        this.hotels = hotels;
    }

    @Override
    public RoomType save(RoomType roomType) {
        rows.put(roomType.id(), roomType);
        return roomType;
    }

    @Override
    public Optional<RoomType> findByIdAndHotelAndOwner(UUID id, UUID hotelId, String ownerSub) {
        return Optional.ofNullable(rows.get(id))
                .filter(r -> r.hotelId().equals(hotelId))
                .filter(r -> ownedBy(hotelId, ownerSub));
    }

    @Override
    public List<RoomType> findAllByHotelAndOwner(UUID hotelId, String ownerSub) {
        return rows.values().stream()
                .filter(r -> r.hotelId().equals(hotelId))
                .filter(r -> ownedBy(hotelId, ownerSub))
                .sorted(Comparator.comparing(RoomType::name).thenComparing(RoomType::id))
                .toList();
    }

    private boolean ownedBy(UUID hotelId, String ownerSub) {
        return hotels.findByIdAndOwner(hotelId, ownerSub).isPresent();
    }
}
