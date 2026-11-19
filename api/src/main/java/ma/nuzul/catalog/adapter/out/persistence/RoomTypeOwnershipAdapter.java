package ma.nuzul.catalog.adapter.out.persistence;

import java.util.UUID;
import ma.nuzul.catalog.RoomTypeOwnership;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Answers {@link RoomTypeOwnership} with one owner-scoped query; no hotel id is needed from the caller. */
@Component
class RoomTypeOwnershipAdapter implements RoomTypeOwnership {

    private final RoomTypeJpaRepository repository;

    RoomTypeOwnershipAdapter(RoomTypeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOwnedBy(UUID roomTypeId, String ownerSub) {
        return repository.existsOwned(roomTypeId, ownerSub);
    }
}
