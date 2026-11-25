package ma.nuzul.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ma.nuzul.inventory.MinPrices;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the month window into a half-open night range [from, first day of month(from) + months) and asks the
 * repository for one grouped query; nothing is queried for an empty room-type list or an empty window.
 */
@Service
@Transactional(readOnly = true)
class MinPricesService implements MinPrices {

    private final RateRepositoryPort rates;

    MinPricesService(RateRepositoryPort rates) {
        this.rates = rates;
    }

    @Override
    public Map<YearMonth, BigDecimal> byMonth(Collection<UUID> roomTypeIds, LocalDate from, int months) {
        Objects.requireNonNull(from, "from");
        if (months < 0) {
            throw new IllegalArgumentException("months must not be negative, was " + months);
        }
        if (roomTypeIds == null || roomTypeIds.isEmpty() || months == 0) {
            return Map.of();
        }
        LocalDate toExclusive = from.withDayOfMonth(1).plusMonths(months);
        return rates.minPriceByMonth(roomTypeIds, from, toExclusive);
    }
}
