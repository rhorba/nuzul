package ma.nuzul.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

/** Batch shape shared by inventory totals and rates: 1..180 non-null entries, no duplicate night. */
class NightBatchTest {

    private static final LocalDate D1 = LocalDate.of(2026, 12, 1);

    private static List<LocalDate> nightsOf(List<LocalDate> entries) {
        return NightBatch.requireValid(entries, Function.identity());
    }

    private static void assertValidationFailed(List<LocalDate> entries) {
        var thrown = catchThrowable(() -> nightsOf(entries));

        assertThat(thrown).isInstanceOf(DomainValidationException.class);
        assertThat(((DomainValidationException) thrown).code()).isEqualTo(DomainValidationException.VALIDATION_FAILED);
    }

    @Test
    void returnsTheNightsInRequestOrder() {
        assertThat(nightsOf(List.of(D1.plusDays(2), D1))).containsExactly(D1.plusDays(2), D1);
    }

    @Test
    void nullOrEmptyIsRejected() {
        assertValidationFailed(null);
        assertValidationFailed(List.of());
    }

    @Test
    void nullEntryIsRejected() {
        assertValidationFailed(Arrays.asList(D1, null));
    }

    @Test
    void duplicateNightIsRejected() {
        assertValidationFailed(List.of(D1, D1.plusDays(1), D1));
    }

    @Test
    void exactlyMaxSizeIsAcceptedAndOneMoreIsRejected() {
        List<LocalDate> nights = new ArrayList<>(
                IntStream.range(0, NightBatch.MAX_SIZE).mapToObj(D1::plusDays).toList());
        assertThat(nightsOf(nights)).hasSize(BookingWindow.DAYS);

        nights.add(D1.minusDays(1));
        assertValidationFailed(nights);
    }
}
