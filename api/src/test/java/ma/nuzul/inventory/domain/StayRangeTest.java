package ma.nuzul.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

/** A stay is the half-open night range [checkIn, checkOut): the checkout day is not a night stayed. */
class StayRangeTest {

    private static final LocalDate D = LocalDate.of(2026, 12, 10);

    private static void assertRefused(Runnable build) {
        Throwable t = catchThrowable(build::run);
        assertThat(t).isInstanceOf(DomainValidationException.class);
        assertThat(((DomainValidationException) t).code()).isEqualTo(DomainValidationException.VALIDATION_FAILED);
    }

    @Test
    void oneNight() {
        var stay = new StayRange(D, D.plusDays(1));

        assertThat(stay.nights()).isEqualTo(1);
    }

    @Test
    void thirtyNightsIsTheMaximum() {
        var stay = new StayRange(D, D.plusDays(StayRange.MAX_NIGHTS));

        assertThat(StayRange.MAX_NIGHTS).isEqualTo(30);
        assertThat(stay.nights()).isEqualTo(30);
    }

    @Test
    void thirtyOneNightsIsRefused() {
        assertRefused(() -> new StayRange(D, D.plusDays(31)));
    }

    @Test
    void checkOutEqualToCheckInIsRefused() {
        assertRefused(() -> new StayRange(D, D));
    }

    @Test
    void checkOutBeforeCheckInIsRefused() {
        assertRefused(() -> new StayRange(D, D.minusDays(1)));
    }

    @Test
    void nullDatesAreRefused() {
        assertRefused(() -> new StayRange(null, D));
        assertRefused(() -> new StayRange(D, null));
    }

    @Test
    void monthBoundaryCountsNightsAcrossMonthsExcludingCheckOut() {
        var stay = new StayRange(LocalDate.of(2027, 1, 30), LocalDate.of(2027, 2, 2));

        assertThat(stay.nights()).isEqualTo(3);
    }

    @Test
    void yearBoundaryCountsNightsAcrossYearsExcludingCheckOut() {
        var stay = new StayRange(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2));

        assertThat(stay.nights()).isEqualTo(3);
    }

    @Test
    void leapDayIsANight() {
        var stay = new StayRange(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1));

        assertThat(stay.nights()).isEqualTo(2);
    }
}
