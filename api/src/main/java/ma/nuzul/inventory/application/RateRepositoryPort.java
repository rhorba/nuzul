package ma.nuzul.inventory.application;

import java.util.List;
import java.util.UUID;
import ma.nuzul.inventory.domain.RateChange;

/**
 * Out-port for per-night rates. Kept apart from {@link InventoryRepositoryPort}: another table with no reserved
 * guard and no refused nights, so neither port (nor its test fake) carries the other's contract.
 */
public interface RateRepositoryPort {

    /**
     * Inserts each night, or updates {@code price_mad} and {@code refundable} on an existing one. Never touches
     * {@code free_cancel_hours}: an insert takes the column default, an update leaves it unchanged.
     */
    void upsertRates(UUID roomTypeId, List<RateChange> changes);
}
