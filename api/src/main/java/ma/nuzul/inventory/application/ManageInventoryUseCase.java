package ma.nuzul.inventory.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ma.nuzul.inventory.domain.InventoryChange;
import org.springframework.modulith.NamedInterface;

/**
 * In-port: a hotel admin sets the per-night room totals of one of their own room types. The batch is all or
 * nothing. A room type owned by someone else behaves like one that does not exist (ROOM_TYPE_NOT_FOUND).
 */
@NamedInterface("admin")
public interface ManageInventoryUseCase {

    void setTotals(String ownerSub, UUID roomTypeId, List<InventoryChange> changes);

    /**
     * The nights of this room type already stocked inside the booking window [today, today + 180 days), whatever
     * their total. Lets a caller fill only the gaps without overwriting totals an admin has set.
     */
    Set<LocalDate> stockedNights(String ownerSub, UUID roomTypeId);
}
