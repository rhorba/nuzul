package ma.nuzul.inventory.application;

import java.util.List;
import java.util.UUID;
import ma.nuzul.inventory.domain.RateChange;

/**
 * In-port: a hotel admin sets the per-night price and refundability of one of their own room types. The batch is
 * all or nothing. A room type owned by someone else behaves like one that does not exist (ROOM_TYPE_NOT_FOUND).
 */
public interface ManageRatesUseCase {

    void setRates(String ownerSub, UUID roomTypeId, List<RateChange> changes);
}
