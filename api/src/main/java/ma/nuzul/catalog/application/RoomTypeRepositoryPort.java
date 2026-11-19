package ma.nuzul.catalog.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.catalog.domain.RoomType;

/**
 * Out-port for room-type persistence. Every read is scoped by the parent hotel AND by that hotel's owner in the
 * query itself, never by hotel id alone (ADR-5, defence in depth).
 */
public interface RoomTypeRepositoryPort {

    /** Inserts or updates. Callers only pass a fresh id or one loaded through a scoped finder. */
    RoomType save(RoomType roomType);

    Optional<RoomType> findByIdAndHotelAndOwner(UUID id, UUID hotelId, String ownerSub);

    /** All room types of the hotel, ordered by name then id. */
    List<RoomType> findAllByHotelAndOwner(UUID hotelId, String ownerSub);
}
