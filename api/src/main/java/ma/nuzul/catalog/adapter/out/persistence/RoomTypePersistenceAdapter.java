package ma.nuzul.catalog.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.catalog.application.RoomTypeRepositoryPort;
import ma.nuzul.catalog.domain.RoomType;
import org.springframework.stereotype.Component;

@Component
class RoomTypePersistenceAdapter implements RoomTypeRepositoryPort {

    private final RoomTypeJpaRepository repository;

    RoomTypePersistenceAdapter(RoomTypeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public RoomType save(RoomType roomType) {
        return toDomain(repository.saveAndFlush(toEntity(roomType)));
    }

    @Override
    public Optional<RoomType> findByIdAndHotelAndOwner(UUID id, UUID hotelId, String ownerSub) {
        return repository.findScoped(id, hotelId, ownerSub).map(RoomTypePersistenceAdapter::toDomain);
    }

    @Override
    public List<RoomType> findAllByHotelAndOwner(UUID hotelId, String ownerSub) {
        return repository.findAllScoped(hotelId, ownerSub).stream()
                .map(RoomTypePersistenceAdapter::toDomain)
                .toList();
    }

    private static RoomTypeJpaEntity toEntity(RoomType r) {
        var e = new RoomTypeJpaEntity();
        e.id = r.id();
        e.hotelId = r.hotelId();
        e.name = r.name();
        e.capacity = (short) r.capacity();
        e.bedConfig = r.bedConfig();
        return e;
    }

    private static RoomType toDomain(RoomTypeJpaEntity e) {
        return new RoomType(e.id, e.hotelId, e.name, e.capacity, e.bedConfig);
    }
}
