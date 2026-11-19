package ma.nuzul.inventory.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ma.nuzul.catalog.RoomTypeOwnership;
import ma.nuzul.inventory.domain.BookingWindow;
import ma.nuzul.inventory.domain.NightBatch;
import ma.nuzul.inventory.domain.RateChange;
import ma.nuzul.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same order as inventory: batch shape, ownership (through catalog's published API, before any write), booking
 * window, then one upsert. Every check runs before the write, so a refusal writes nothing.
 */
@Service
@Transactional
class RateAdminService implements ManageRatesUseCase {

    static final String ROOM_TYPE_NOT_FOUND = InventoryAdminService.ROOM_TYPE_NOT_FOUND;

    private final RateRepositoryPort rates;
    private final RoomTypeOwnership ownership;
    private final Clock clock;

    RateAdminService(RateRepositoryPort rates, RoomTypeOwnership ownership, Clock clock) {
        this.rates = rates;
        this.ownership = ownership;
        this.clock = clock;
    }

    @Override
    public void setRates(String ownerSub, UUID roomTypeId, List<RateChange> changes) {
        List<LocalDate> nights = NightBatch.requireValid(changes, RateChange::night);
        if (!ownership.isOwnedBy(roomTypeId, ownerSub)) {
            throw new NotFoundException(ROOM_TYPE_NOT_FOUND, "Room type " + roomTypeId + " not found");
        }
        new BookingWindow(LocalDate.now(clock)).requireContains(nights);

        rates.upsertRates(roomTypeId, changes);
    }
}
