package ma.nuzul.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

/** The free-cancellation rule, pure Java (test strategy: cancellation policy as a unit). */
class CancellationPolicyTest {

    /* A fixed offset, not Africa/Casablanca: Morocco's offset differs between JDK tzdata releases. */
    private static final ZoneId UTC_PLUS_ONE = ZoneOffset.ofHours(1);
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 12, 10);

    @Test
    void freeUntilCheckInDayMidnightMinusTheHours() {
        var policy = CancellationPolicy.forStay(true, 48, CHECK_IN, UTC_PLUS_ONE);

        // 10 Dec 00:00 at UTC+1 is 9 Dec 23:00 UTC; minus 48 h is 7 Dec 23:00 UTC.
        assertThat(policy.freeUntil()).isEqualTo(Instant.parse("2026-12-07T23:00:00Z"));
        assertThat(policy.refundable()).isTrue();
    }

    @Test
    void theDeadlineItselfIsNoLongerFree() {
        var policy = CancellationPolicy.forStay(true, 24, CHECK_IN, UTC_PLUS_ONE);
        Instant deadline = policy.freeUntil();

        assertThat(policy.isFreeAt(deadline.minusNanos(1))).isTrue();
        assertThat(policy.isFreeAt(deadline)).isFalse();
        assertThat(policy.isFreeAt(deadline.plusSeconds(1))).isFalse();
    }

    @Test
    void zeroHoursIsFreeUntilTheCheckInDayStarts() {
        var policy = CancellationPolicy.forStay(true, 0, CHECK_IN, UTC_PLUS_ONE);

        assertThat(policy.freeUntil()).isEqualTo(Instant.parse("2026-12-09T23:00:00Z"));
    }

    @Test
    void aCheckInDayWhoseMidnightDoesNotExistStartsAtTheFirstValidInstant() {
        // Santiago springs forward at 00:00 (to 01:00) on 6 Sep 2026: that day starts at 01:00 local, 04:00 UTC.
        var policy = CancellationPolicy.forStay(true, 0, LocalDate.of(2026, 9, 6), ZoneId.of("America/Santiago"));

        assertThat(policy.freeUntil()).isEqualTo(Instant.parse("2026-09-06T04:00:00Z"));
    }

    @Test
    void aNonRefundableStayIsNeverFree() {
        var policy = CancellationPolicy.forStay(false, 48, CHECK_IN, UTC_PLUS_ONE);

        assertThat(policy).isEqualTo(CancellationPolicy.NON_REFUNDABLE);
        assertThat(policy.freeUntil()).isNull();
        assertThat(policy.isFreeAt(Instant.EPOCH)).isFalse();
        assertThat(new CancellationPolicy(false, Instant.MAX).freeUntil())
                .as("a deadline without refund is dropped")
                .isNull();
    }

    @Test
    void invalidTermsAreRefused() {
        assertThatThrownBy(() -> new CancellationPolicy(true, null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> CancellationPolicy.forStay(true, -1, CHECK_IN, UTC_PLUS_ONE))
                .isInstanceOf(DomainValidationException.class);
    }
}
