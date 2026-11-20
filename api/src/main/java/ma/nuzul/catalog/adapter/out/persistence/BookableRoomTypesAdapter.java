package ma.nuzul.catalog.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import ma.nuzul.catalog.BookableRoomTypes;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Answers {@link BookableRoomTypes} with one query joining the room type to its ACTIVE hotel. */
@Component
class BookableRoomTypesAdapter implements BookableRoomTypes {

    private final RoomTypeJpaRepository repository;

    BookableRoomTypesAdapter(RoomTypeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> hotelOf(UUID roomTypeId) {
        return roomTypeId == null ? Optional.empty() : repository.findBookableHotelId(roomTypeId);
    }
}
