package ma.nuzul.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;
import java.util.List;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

/** The booking window is half-open: [today, today + 180 days). */
class BookingWindowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private final BookingWindow window = new BookingWindow(TODAY);

    @Test
    void yesterdayIsOutside() {
        assertThat(window.contains(TODAY.minusDays(1))).isFalse();
    }

    @Test
    void todayIsInside() {
        assertThat(window.contains(TODAY)).isTrue();
    }

    @Test
    void lastAllowedNightIsTodayPlus179() {
        assertThat(window.contains(TODAY.plusDays(179))).isTrue();
        assertThat(window.endExclusive()).isEqualTo(TODAY.plusDays(180));
    }

    @Test
    void todayPlus180IsOutside() {
        assertThat(window.contains(TODAY.plusDays(180))).isFalse();
    }

    @Test
    void nightsInsideTheWindowPass() {
        assertThatCode(() -> window.requireContains(List.of(TODAY, TODAY.plusDays(179))))
                .doesNotThrowAnyException();
    }

    @Test
    void offendingNightsAreListedAscendingWithNightOutsideWindowCode() {
        var thrown = (DomainValidationException)
                catchThrowable(() -> window.requireContains(List.of(TODAY.plusDays(180), TODAY, TODAY.minusDays(1))));

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo(BookingWindow.NIGHT_OUTSIDE_WINDOW);
        assertThat(thrown.properties()).containsEntry("nights", List.of("2026-11-18", "2027-05-18"));
    }

    @Test
    void todayIsRequired() {
        assertThatThrownBy(() -> new BookingWindow(null)).isInstanceOf(DomainValidationException.class);
    }
}
