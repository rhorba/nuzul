package ma.nuzul.catalog.adapter.out.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.nuzul.catalog.HotelSnapshot;
import ma.nuzul.catalog.HotelSnapshots;
import ma.nuzul.catalog.domain.HotelStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@link HotelSnapshots} with two queries per call whatever the page size: the hotels, then every room type
 * id of those hotels in one batched query (never one query per hotel).
 */
@Component
class HotelSnapshotsAdapter implements HotelSnapshots {

    private final HotelJpaRepository hotels;
    private final RoomTypeJpaRepository roomTypes;

    HotelSnapshotsAdapter(HotelJpaRepository hotels, RoomTypeJpaRepository roomTypes) {
        this.hotels = hotels;
        this.roomTypes = roomTypes;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HotelSnapshot> find(UUID hotelId) {
        return hotels.findById(hotelId).map(h -> toSnapshot(h, roomTypeIdsOf(List.of(h))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HotelSnapshot> activePage(@Nullable UUID afterId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
        List<HotelJpaEntity> page = afterId == null
                ? hotels.findByStatusOrderByIdAsc(HotelStatus.ACTIVE, Limit.of(limit))
                : hotels.findByStatusAndIdGreaterThanOrderByIdAsc(HotelStatus.ACTIVE, afterId, Limit.of(limit));
        if (page.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<UUID>> roomTypeIds = roomTypeIdsOf(page);
        return page.stream().map(h -> toSnapshot(h, roomTypeIds)).toList();
    }

    private Map<UUID, List<UUID>> roomTypeIdsOf(List<HotelJpaEntity> page) {
        return roomTypes.findRoomTypeIdsOf(page.stream().map(h -> h.id).toList()).stream()
                .collect(Collectors.groupingBy(
                        RoomTypeJpaRepository.HotelRoomType::getHotelId,
                        Collectors.mapping(RoomTypeJpaRepository.HotelRoomType::getRoomTypeId, Collectors.toList())));
    }

    private static HotelSnapshot toSnapshot(HotelJpaEntity h, Map<UUID, List<UUID>> roomTypeIds) {
        return new HotelSnapshot(
                h.id,
                h.name,
                h.city,
                toDouble(h.lat),
                toDouble(h.lon),
                h.stars == null ? null : h.stars.intValue(),
                h.amenities == null ? List.of() : List.of(h.amenities),
                h.status == HotelStatus.ACTIVE,
                roomTypeIds.getOrDefault(h.id, List.of()));
    }

    private static @Nullable Double toDouble(@Nullable BigDecimal coordinate) {
        return coordinate == null ? null : coordinate.doubleValue();
    }
}
