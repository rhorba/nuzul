package ma.nuzul.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The month window of MinPrices against the in-memory rate fake; the grouped SQL is proven by MinPricesIT. */
class MinPricesServiceTest {

    private static final LocalDate FROM = LocalDate.of(2027, 1, 15);

    private final InMemoryRateRepository repo = new InMemoryRateRepository();
    private final MinPricesService service = new MinPricesService(repo);
    private final UUID roomTypeId = UUID.randomUUID();

    private void rate(LocalDate night, String price) {
        repo.seed(roomTypeId, night, new InMemoryRateRepository.Row(new BigDecimal(price), true, 48));
    }

    @Test
    void windowRunsFromFromToTheEndOfTheLastMonth() {
        rate(FROM.minusDays(1), "1.00"); // before from: excluded although it is in January
        rate(FROM, "300.00");
        rate(LocalDate.of(2027, 3, 31), "200.00"); // last night of the third month: included
        rate(LocalDate.of(2027, 4, 1), "5.00"); // first night of the fourth month: outside the window

        assertThat(service.byMonth(List.of(roomTypeId), FROM, 3))
                .isEqualTo(Map.of(
                        YearMonth.of(2027, 1), new BigDecimal("300.00"),
                        YearMonth.of(2027, 3), new BigDecimal("200.00")));
    }

    @Test
    void emptyInputOrZeroMonthsAnswersEmptyWithoutQuerying() {
        rate(FROM, "300.00");

        assertThat(service.byMonth(List.of(), FROM, 3)).isEmpty();
        assertThat(service.byMonth(null, FROM, 3)).isEmpty();
        assertThat(service.byMonth(List.of(roomTypeId), FROM, 0)).isEmpty();
        assertThat(repo.minPriceCalls).isZero();
    }

    @Test
    void negativeMonthsOrMissingFromIsRefused() {
        assertThatThrownBy(() -> service.byMonth(List.of(roomTypeId), FROM, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.byMonth(List.of(roomTypeId), null, 1))
                .isInstanceOf(NullPointerException.class);
    }
}
