package ma.nuzul.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Story 3.2: the cheapest rate per month for the search index, through the grouped SQL on real Postgres. Rates are
 * inserted directly (rate is not partitioned and has no window check in SQL), on fresh room types: no cleanup.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MinPricesIT {

    private static final LocalDate FROM = LocalDate.of(2027, 1, 15);

    @Autowired
    MinPrices minPrices;

    @Autowired
    JdbcTemplate jdbc;

    private UUID roomType() {
        return new ReserveFixtures(jdbc).roomType("owner-" + UUID.randomUUID());
    }

    private void rate(UUID roomTypeId, LocalDate night, String price) {
        new ReserveFixtures(jdbc).rate(roomTypeId, night, price);
    }

    @Test
    void cheapestRatePerMonthAcrossTheGivenRoomTypes() {
        UUID a = roomType();
        UUID b = roomType();
        UUID notAsked = roomType();
        rate(a, FROM.minusDays(1), "1.00"); // before from: excluded although it is in January
        rate(a, FROM, "300.00");
        rate(b, LocalDate.of(2027, 1, 31), "250.50"); // last night of January
        rate(a, LocalDate.of(2027, 2, 1), "400.00"); // first night of February
        rate(b, LocalDate.of(2027, 2, 1), "450.00");
        // March has no rate at all.
        rate(a, LocalDate.of(2027, 4, 1), "2.00"); // outside the 3-month window
        rate(notAsked, LocalDate.of(2027, 1, 20), "9.99"); // a room type not asked for

        Map<YearMonth, BigDecimal> byMonth = minPrices.byMonth(List.of(a, b), FROM, 3);

        assertThat(byMonth)
                .isEqualTo(Map.of(
                        YearMonth.of(2027, 1), new BigDecimal("250.50"),
                        YearMonth.of(2027, 2), new BigDecimal("400.00")));
    }

    @Test
    void theLastNightOfAMonthAndTheFirstOfTheNextGoToTheirOwnMonths() {
        UUID a = roomType();
        rate(a, LocalDate.of(2027, 2, 28), "100.00");
        rate(a, LocalDate.of(2027, 3, 1), "50.00");

        assertThat(minPrices.byMonth(List.of(a), LocalDate.of(2027, 2, 1), 2))
                .isEqualTo(Map.of(
                        YearMonth.of(2027, 2), new BigDecimal("100.00"),
                        YearMonth.of(2027, 3), new BigDecimal("50.00")));
    }

    @Test
    void roomTypesWithoutRatesOrNoRoomTypesGiveAnEmptyMap() {
        assertThat(minPrices.byMonth(List.of(roomType()), FROM, 6)).isEmpty();
        assertThat(minPrices.byMonth(List.of(), FROM, 6)).isEmpty();
    }
}
