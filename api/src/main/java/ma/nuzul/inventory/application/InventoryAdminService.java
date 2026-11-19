package ma.nuzul.inventory.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ma.nuzul.catalog.RoomTypeOwnership;
import ma.nuzul.inventory.domain.BookingWindow;
import ma.nuzul.inventory.domain.InventoryChange;
import ma.nuzul.inventory.domain.NightBatch;
import ma.nuzul.shared.error.ConflictException;
import ma.nuzul.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks, in order: batch shape, ownership (through catalog's published API, before any write), booking window,
 * then one guarded upsert. Any refused night throws, which rolls back the nights already written in this batch.
 */
@Service
@Transactional
class InventoryAdminService implements ManageInventoryUseCase {

    static final String ROOM_TYPE_NOT_FOUND = "ROOM_TYPE_NOT_FOUND";
    static final String INVENTORY_BELOW_RESERVED = "INVENTORY_BELOW_RESERVED";

    private final InventoryRepositoryPort inventory;
    private final RoomTypeOwnership ownership;
    private final Clock clock;

    InventoryAdminService(InventoryRepositoryPort inventory, RoomTypeOwnership ownership, Clock clock) {
        this.inventory = inventory;
        this.ownership = ownership;
        this.clock = clock;
    }

    @Override
    public void setTotals(String ownerSub, UUID roomTypeId, List<InventoryChange> changes) {
        List<LocalDate> nights = NightBatch.requireValid(changes, InventoryChange::night);
        if (!ownership.isOwnedBy(roomTypeId, ownerSub)) {
            throw new NotFoundException(ROOM_TYPE_NOT_FOUND, "Room type " + roomTypeId + " not found");
        }
        new BookingWindow(LocalDate.now(clock)).requireContains(nights);

        Set<LocalDate> applied = inventory.upsertTotals(roomTypeId, changes);
        List<String> refused = nights.stream()
                .filter(n -> !applied.contains(n))
                .sorted()
                .map(LocalDate::toString)
                .toList();
        if (!refused.isEmpty()) {
            throw new ConflictException(
                    INVENTORY_BELOW_RESERVED,
                    "total cannot be lower than the rooms already reserved",
                    Map.of("nights", refused));
        }
    }
}
