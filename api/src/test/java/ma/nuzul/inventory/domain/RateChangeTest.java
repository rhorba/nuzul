package ma.nuzul.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A night's price in MAD: exact BigDecimal, > 0, at most 2 decimals, fits NUMERIC(10,2). */
class RateChangeTest {

    private static final LocalDate NIGHT = LocalDate.of(2026, 12, 1);

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "850.00", "1234.5", "10.000", "99999999.99"})
    void pricesWithinBoundsAreAcceptedUnchanged(String price) {
        var change = new RateChange(NIGHT, new BigDecimal(price), true);

        assertThat(change.priceMad()).isEqualTo(new BigDecimal(price));
        assertThat(change.refundable()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-0.01", "-5", "10.005", "0.001", "100000000", "99999999.991"})
    void pricesOutOfBoundsOrTooPreciseAreRejected(String price) {
        assertThatThrownBy(() -> new RateChange(NIGHT, new BigDecimal(price), false))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void priceIsRequired() {
        assertThatThrownBy(() -> new RateChange(NIGHT, null, true)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void nightIsRequired() {
        assertThatThrownBy(() -> new RateChange(null, BigDecimal.TEN, true))
                .isInstanceOf(DomainValidationException.class);
    }
}
