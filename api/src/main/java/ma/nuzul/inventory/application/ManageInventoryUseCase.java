package ma.nuzul.inventory.application;

import java.util.List;
import java.util.UUID;
import ma.nuzul.inventory.domain.InventoryChange;

/**
 * In-port: a hotel admin sets the per-night room totals of one of their own room types. The batch is all or
 * nothing. A room type owned by someone else behaves like one that does not exist (ROOM_TYPE_NOT_FOUND).
 */
public interface ManageInventoryUseCase {

    void setTotals(String ownerSub, UUID roomTypeId, List<InventoryChange> changes);
}
