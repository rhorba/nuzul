package ma.nuzul.catalog.application;

import java.util.Optional;
import java.util.UUID;
import ma.nuzul.catalog.domain.Hotel;

/** Out-port for hotel persistence. Every read is owner-scoped in the query itself (ADR-5). */
public interface HotelRepositoryPort {

    /** Inserts a hotel whose version is null, otherwise updates it (optimistic lock on version). */
    Hotel save(Hotel hotel);

    Optional<Hotel> findByIdAndOwner(UUID id, String ownerSub);

    /** The owner's hotels, newest first (createdAt DESC, then id DESC). */
    PageResult<Hotel> findAllByOwner(String ownerSub, int page, int size);
}
