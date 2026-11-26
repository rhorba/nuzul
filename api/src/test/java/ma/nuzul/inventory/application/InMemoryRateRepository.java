package ma.nuzul.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import ma.nuzul.inventory.domain.RateChange;

/**
 * Test fake for {@link RateRepositoryPort}. Mirrors the SQL: insert with free_cancel_hours 48, or update price and
 * refundable only, leaving free_cancel_hours as it was. There is no rollback here, the IT proves that.
 */
class InMemoryRateRepository implements RateRepositoryPort {

    record Row(BigDecimal priceMad, boolean refundable, int freeCancelHours) {}

    static final int DEFAULT_FREE_CANCEL_HOURS = 48;

    final Map<UUID, Map<LocalDate, Row>> rows = new TreeMap<>();
    int upsertCalls;
    int minPriceCalls;

    void seed(UUID roomTypeId, LocalDate night, Row row) {
        rows.computeIfAbsent(roomTypeId, k -> new TreeMap<>()).put(night, row);
    }

    Row row(UUID roomTypeId, LocalDate night) {
        return rows.getOrDefault(roomTypeId, Map.of()).get(night);
    }

    @Override
    public void upsertRates(UUID roomTypeId, List<RateChange> changes) {
        upsertCalls++;
        var nights = rows.computeIfAbsent(roomTypeId, k -> new TreeMap<>());
        for (RateChange c : changes) {
            Row current = nights.get(c.night());
            int hours = current == null ? DEFAULT_FREE_CANCEL_HOURS : current.freeCancelHours();
            nights.put(c.night(), new Row(c.priceMad(), c.refundable(), hours));
        }
    }

    @Override
    public PricedNights priceNights(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut) {
        int nights = 0;
        BigDecimal total = new BigDecimal("0.00");
        boolean allRefundable = true;
        int maxHours = 0;
        for (LocalDate night : checkIn.datesUntil(checkOut).toList()) {
            Row row = row(roomTypeId, night);
            if (row != null) {
                nights++;
                total = total.add(row.priceMad());
                allRefundable &= row.refundable();
                maxHours = Math.max(maxHours, row.freeCancelHours());
            }
        }
        return new PricedNights(nights, total, nights > 0 && allRefundable, maxHours);
    }

    @Override
    public Map<YearMonth, BigDecimal> minPriceByMonth(
            Collection<UUID> roomTypeIds, LocalDate from, LocalDate toExclusive) {
        minPriceCalls++;
        Map<YearMonth, BigDecimal> byMonth = new TreeMap<>();
        for (UUID roomTypeId : roomTypeIds) {
            rows.getOrDefault(roomTypeId, Map.of()).forEach((night, row) -> {
                if (!night.isBefore(from) && night.isBefore(toExclusive)) {
                    byMonth.merge(YearMonth.from(night), row.priceMad(), BigDecimal::min);
                }
            });
        }
        return byMonth;
    }
}
