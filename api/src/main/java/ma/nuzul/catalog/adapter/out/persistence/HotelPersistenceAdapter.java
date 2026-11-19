package ma.nuzul.catalog.adapter.out.persistence;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.catalog.application.HotelRepositoryPort;
import ma.nuzul.catalog.application.PageResult;
import ma.nuzul.catalog.domain.Hotel;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
class HotelPersistenceAdapter implements HotelRepositoryPort {

    /** Newest first; id breaks createdAt ties so paging is deterministic. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final HotelJpaRepository repository;

    HotelPersistenceAdapter(HotelJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Hotel save(Hotel hotel) {
        // Flush so the returned version reflects the INSERT/UPDATE (and a stale version fails here, not later).
        return toDomain(repository.saveAndFlush(toEntity(hotel)));
    }

    @Override
    public Optional<Hotel> findByIdAndOwner(UUID id, String ownerSub) {
        return repository.findByIdAndOwnerSub(id, ownerSub).map(HotelPersistenceAdapter::toDomain);
    }

    @Override
    public PageResult<Hotel> findAllByOwner(String ownerSub, int page, int size) {
        var result = repository.findAllByOwnerSub(ownerSub, PageRequest.of(page, size, NEWEST_FIRST));
        return new PageResult<>(
                result.map(HotelPersistenceAdapter::toDomain).getContent(), page, size, result.getTotalElements());
    }

    private static HotelJpaEntity toEntity(Hotel h) {
        var e = new HotelJpaEntity();
        e.id = h.id();
        e.ownerSub = h.ownerSub();
        e.name = h.name();
        e.city = h.city();
        e.address = h.address();
        e.stars = h.stars() == null ? null : h.stars().shortValue();
        e.amenities = h.amenities().toArray(String[]::new);
        e.lat = h.lat();
        e.lon = h.lon();
        e.status = h.status();
        e.createdAt = h.createdAt();
        e.version = h.version();
        return e;
    }

    private static Hotel toDomain(HotelJpaEntity e) {
        return new Hotel(
                e.id,
                e.ownerSub,
                e.name,
                e.city,
                e.address,
                e.stars == null ? null : e.stars.intValue(),
                e.amenities == null ? null : Arrays.asList(e.amenities),
                e.lat,
                e.lon,
                e.status,
                e.createdAt,
                e.version);
    }
}
