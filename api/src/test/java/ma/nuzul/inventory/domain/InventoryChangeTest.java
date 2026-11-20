package ma.nuzul.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A night's total sellable rooms: 0..1000, never null night. */
class InventoryChangeTest {

    private static final LocalDate NIGHT = LocalDate.of(2026, 12, 1);

    @ParameterizedTest
    @ValueSource(ints = {0, 1, InventoryChange.MAX_TOTAL})
    void totalsWithinBoundsAreAccepted(int total) {
        assertThat(new InventoryChange(NIGHT, total).total()).isEqualTo(total);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, InventoryChange.MAX_TOTAL + 1})
    void totalsOutOfBoundsAreRejected(int total) {
        assertThatThrownBy(() -> new InventoryChange(NIGHT, total)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void nightIsRequired() {
        assertThatThrownBy(() -> new InventoryChange(null, 1)).isInstanceOf(DomainValidationException.class);
    }
}
