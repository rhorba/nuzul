package ma.nuzul.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import ma.nuzul.inventory.SoldOutException;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

/** Stay pricing against an in-memory rate table; no Spring. */
class StayPricingServiceTest {

    private static final LocalDate IN = LocalDate.of(2026, 12, 1);

    private final UUID roomTypeId = UUID.randomUUID();
    private final InMemoryRateRepository rates = new InMemoryRateRepository();
    private final StayPricingService pricing = new StayPricingService(rates);

    private void rate(int plusDays, String price) {
        rates.seed(roomTypeId, IN.plusDays(plusDays), new InMemoryRateRepository.Row(new BigDecimal(price), true, 48));
    }

    @Test
    void sumsEachNightTimesRoomsExactly() {
        rate(0, "650.10");
        rate(1, "780.25");
        rate(2, "999.99");

        assertThat(pricing.price(roomTypeId, IN, IN.plusDays(3), 2)).isEqualTo(new BigDecimal("4860.68"));
    }

    @Test
    void checkOutNightIsNotCharged() {
        rate(0, "500.00");
        rate(1, "9999.00");

        assertThat(pricing.price(roomTypeId, IN, IN.plusDays(1), 1)).isEqualByComparingTo("500.00");
    }

    @Test
    void aStayWithAnUnpricedNightIsSoldOut() {
        rate(0, "500.00");
        rate(2, "500.00");

        assertThatThrownBy(() -> pricing.price(roomTypeId, IN, IN.plusDays(3), 1))
                .isInstanceOf(SoldOutException.class);
    }

    @Test
    void aTotalTooLargeForTheReservationColumnIsRefused() {
        for (int i = 0; i < 30; i++) {
            rate(i, "99999999.99");
        }

        assertThatThrownBy(() -> pricing.price(roomTypeId, IN, IN.plusDays(30), 9))
                .isInstanceOf(DomainValidationException.class);
        assertThat(pricing.price(roomTypeId, IN, IN.plusDays(30), 1)).isEqualByComparingTo("2999999999.70");
    }

    @Test
    void invalidInputIsRefusedBeforeAnyQuery() {
        assertThatThrownBy(() -> pricing.price(null, IN, IN.plusDays(1), 1))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> pricing.price(roomTypeId, IN, IN, 1)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> pricing.price(roomTypeId, IN, IN.plusDays(1), 10))
                .isInstanceOf(DomainValidationException.class);
    }
}
