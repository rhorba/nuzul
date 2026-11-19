package ma.nuzul.catalog.adapter.in.web;

import java.util.UUID;
import ma.nuzul.catalog.domain.RoomType;

record RoomTypeResponse(UUID id, UUID hotelId, String name, int capacity, String bedConfig) {

    static RoomTypeResponse from(RoomType r) {
        return new RoomTypeResponse(r.id(), r.hotelId(), r.name(), r.capacity(), r.bedConfig());
    }
}
