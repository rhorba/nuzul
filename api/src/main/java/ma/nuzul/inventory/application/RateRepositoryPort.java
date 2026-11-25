package ma.nuzul.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * How many nights of [checkIn, checkOut) have a rate, the sum of their prices (0.00 when none), whether all of them
     * are refundable, and the largest free_cancel_hours among them.
     */
    PricedNights priceNights(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut);

    /**
     * The lowest price per calendar month over the given room types' nights in [from, toExclusive); months with no
     * rate are absent.
     */
    Map<YearMonth, BigDecimal> minPriceByMonth(Collection<UUID> roomTypeIds, LocalDate from, LocalDate toExclusive);

    record PricedNights(int nights, BigDecimal totalMad, boolean allRefundable, int maxFreeCancelHours) {}
}
