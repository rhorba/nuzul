package ma.nuzul.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.payment.ChargeResult;
import ma.nuzul.payment.PaymentStatus;
import ma.nuzul.payment.Payments;
import ma.nuzul.reservation.domain.CancellationPolicy;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import ma.nuzul.shared.error.CodedException;
import ma.nuzul.shared.error.ConflictException;
import ma.nuzul.shared.error.DomainValidationException;
import ma.nuzul.shared.error.GoneException;
import ma.nuzul.shared.error.NotFoundException;
import ma.nuzul.shared.error.PaymentRequiredException;
import org.junit.jupiter.api.Test;

/** Every confirm branch against in-memory reservations and a scripted Payments; no Spring, no transactions. */
class ConfirmServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String GUEST = "guest-1";
    private static final BigDecimal TOTAL = new BigDecimal("1400.00");

    private final Map<UUID, Reservation> rows = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final UUID paymentId = UUID.randomUUID();
    private PaymentStatus beginStatus = PaymentStatus.PENDING;
    private ChargeResult chargeAnswer = new ChargeResult.Approved("ch_1");
    /** Runs during the charge: lets a test expire or confirm the hold while the provider is being called. */
    private Runnable duringCharge = () -> {};
    /** The shared payment's state, as the conditional updates see it. */
    private boolean captured;

    private boolean refundClaimed;

    private final ReservationRepositoryPort reservations = new ReservationRepositoryPort() {
        @Override
        public boolean insertIfKeyAbsent(Reservation reservation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Reservation> findByIdempotencyKey(String guestSub, UUID idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Reservation> findForGuest(UUID id, String guestSub) {
            return Optional.ofNullable(rows.get(id)).filter(r -> r.guestSub().equals(guestSub));
        }

        @Override
        public Optional<ReservationStatus> findStatus(UUID id) {
            return Optional.ofNullable(rows.get(id)).map(Reservation::status);
        }

        @Override
        public boolean confirmIfHeld(UUID id, Instant now) {
            Reservation r = rows.get(id);
            if (r == null
                    || r.status() != ReservationStatus.HELD
                    || !r.holdExpiresAt().isAfter(now)) {
                return false;
            }
            rows.put(id, withStatus(r, ReservationStatus.CONFIRMED));
            return true;
        }

        @Override
        public boolean cancelIf(UUID id, ReservationStatus from) {
            throw new UnsupportedOperationException("not used here");
        }
    };

    private final Payments payments = new Payments() {
        @Override
        public Active begin(UUID reservationId, BigDecimal amountMad) {
            calls.add("begin " + amountMad);
            return new Active(paymentId, beginStatus, amountMad);
        }

        @Override
        public ChargeResult charge(UUID id, String paymentToken) {
            calls.add("charge " + paymentToken);
            duringCharge.run();
            return chargeAnswer;
        }

        @Override
        public boolean capture(UUID id, String providerRef) {
            if (refundClaimed) {
                return false;
            }
            captured = true;
            calls.add("capture " + providerRef);
            return true;
        }

        @Override
        public Optional<UUID> claimRefund(UUID reservationId) {
            throw new UnsupportedOperationException("not used by confirm");
        }

        @Override
        public void finishRefund(UUID paymentId) {
            throw new UnsupportedOperationException("not used by confirm");
        }

        @Override
        public Optional<Refund> refundOf(UUID reservationId) {
            throw new UnsupportedOperationException("not used by confirm");
        }

        @Override
        public boolean refund(UUID id, String providerRef) {
            if (captured) {
                return false;
            }
            if (refundClaimed) {
                return true; // already REFUNDED: no second provider call
            }
            refundClaimed = true;
            calls.add("refund " + providerRef);
            return true;
        }
    };

    private final ConfirmService service =
            new ConfirmService(reservations, new ConfirmTransactions(reservations, payments, CLOCK), payments, CLOCK);

    private static Reservation withStatus(Reservation r, ReservationStatus status) {
        return new Reservation(
                r.id(),
                r.guestSub(),
                r.hotelId(),
                r.roomTypeId(),
                r.checkIn(),
                r.checkOut(),
                r.rooms(),
                status,
                r.totalMad(),
                r.holdExpiresAt(),
                r.idempotencyKey(),
                r.guestName(),
                r.guestEmail(),
                r.cancellationPolicy());
    }

    private UUID reservation(ReservationStatus status, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        rows.put(
                id,
                new Reservation(
                        id,
                        GUEST,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        LocalDate.of(2026, 12, 1),
                        LocalDate.of(2026, 12, 3),
                        1,
                        status,
                        TOTAL,
                        expiresAt,
                        UUID.randomUUID(),
                        "Amina",
                        "a@example.ma",
                        CancellationPolicy.NON_REFUNDABLE));
        return id;
    }

    private static CodedException thrown(Runnable call) {
        Throwable t = catchThrowable(call::run);
        assertThat(t).isInstanceOf(CodedException.class);
        return (CodedException) t;
    }

    @Test
    void chargesThenConfirmsAndCapturesTogether() {
        UUID id = reservation(ReservationStatus.HELD, NOW.plusSeconds(60));

        var confirmed = service.confirm(GUEST, id, "tok_4242");

        assertThat(confirmed)
                .isEqualTo(new ConfirmReservationUseCase.Confirmed(id, ReservationStatus.CONFIRMED, TOTAL));
        assertThat(calls).containsExactly("begin 1400.00", "charge tok_4242", "capture ch_1");
        assertThat(rows.get(id).status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void aConfirmedReservationAnswersAgainWithoutPaying() {
        UUID id = reservation(ReservationStatus.CONFIRMED, NOW.minusSeconds(60));

        assertThat(service.confirm(GUEST, id, "tok").status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(calls).isEmpty();
    }

    @Test
    void anAlreadyCapturedPaymentMeansAConcurrentConfirmWon() {
        UUID id = reservation(ReservationStatus.HELD, NOW.plusSeconds(60));
        beginStatus = PaymentStatus.CAPTURED;

        assertThat(service.confirm(GUEST, id, "tok").status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(calls).containsExactly("begin 1400.00");
    }

    @Test
    void aDeclinedCardIs402AndTheHoldStaysHeld() {
        UUID id = reservation(ReservationStatus.HELD, NOW.plusSeconds(60));
        chargeAnswer = new ChargeResult.Declined("card declined");

        var e = thrown(() -> service.confirm(GUEST, id, "tok_0002"));

        assertThat(e).isInstanceOf(PaymentRequiredException.class);
        assertThat(e.code()).isEqualTo(ConfirmService.PAYMENT_FAILED);
        assertThat(rows.get(id).status()).isEqualTo(ReservationStatus.HELD);
        assertThat(calls).doesNotContain("capture ch_1");
    }

    @Test
    void aHoldThatExpiresDuringTheChargeIsRefundedAnd410() {
        UUID id = reservation(ReservationStatus.HELD, NOW.plusSeconds(60));
        duringCharge = () -> rows.put(id, withStatus(rows.get(id), ReservationStatus.EXPIRED));

        var e = thrown(() -> service.confirm(GUEST, id, "tok"));

        assertThat(e).isInstanceOf(GoneException.class);
        assertThat(e.code()).isEqualTo(ConfirmService.HOLD_EXPIRED);
        assertThat(calls).containsExactly("begin 1400.00", "charge tok", "refund ch_1");
    }

    @Test
    void aConcurrentConfirmThatFinishedFirstIsNotRefunded() {
        UUID id = reservation(ReservationStatus.HELD, NOW.plusSeconds(60));
        duringCharge = () -> {
            rows.put(id, withStatus(rows.get(id), ReservationStatus.CONFIRMED));
            captured = true;
        };

        assertThat(service.confirm(GUEST, id, "tok").status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(calls).containsExactly("begin 1400.00", "charge tok");
    }

    @Test
    void aRefundThatClaimedThePaymentFirstRollsTheConfirmationBack() {
        UUID id = reservation(ReservationStatus.HELD, NOW.plusSeconds(60));
        duringCharge = () -> refundClaimed = true; // a twin confirm, past expiry, claimed the shared payment

        var e = thrown(() -> service.confirm(GUEST, id, "tok"));

        assertThat(e).isInstanceOf(GoneException.class);
        assertThat(calls).containsExactly("begin 1400.00", "charge tok");
    }

    @Test
    void aReservationCancelledWhileBeingPaidIsRefundedAnd409() {
        UUID id = reservation(ReservationStatus.HELD, NOW.plusSeconds(60));
        duringCharge = () -> rows.put(id, withStatus(rows.get(id), ReservationStatus.CANCELLED));

        var e = thrown(() -> service.confirm(GUEST, id, "tok"));

        assertThat(e).isInstanceOf(ConflictException.class);
        assertThat(e.code()).isEqualTo(ConfirmService.RESERVATION_CANCELLED);
        assertThat(calls).containsExactly("begin 1400.00", "charge tok", "refund ch_1");
    }

    @Test
    void expiredHoldsAre410BeforeAnyPayment() {
        UUID pastDeadline = reservation(ReservationStatus.HELD, NOW);
        UUID markedExpired = reservation(ReservationStatus.EXPIRED, NOW.plusSeconds(60));

        assertThat(thrown(() -> service.confirm(GUEST, pastDeadline, "tok"))).isInstanceOf(GoneException.class);
        assertThat(thrown(() -> service.confirm(GUEST, markedExpired, "tok"))).isInstanceOf(GoneException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void aCancelledReservationIs409() {
        UUID id = reservation(ReservationStatus.CANCELLED, NOW.plusSeconds(60));

        var e = thrown(() -> service.confirm(GUEST, id, "tok"));

        assertThat(e).isInstanceOf(ConflictException.class);
        assertThat(e.code()).isEqualTo(ConfirmService.RESERVATION_CANCELLED);
    }

    @Test
    void anotherGuestsReservationIsNotFound() {
        UUID id = reservation(ReservationStatus.HELD, NOW.plusSeconds(60));

        var e = thrown(() -> service.confirm("intruder", id, "tok"));

        assertThat(e).isInstanceOf(NotFoundException.class);
        assertThat(e.code()).isEqualTo(ConfirmService.RESERVATION_NOT_FOUND);
        assertThat(calls).isEmpty();
    }

    @Test
    void missingArgumentsAreRefused() {
        UUID id = reservation(ReservationStatus.HELD, NOW.plusSeconds(60));

        assertThat(thrown(() -> service.confirm(GUEST, id, " "))).isInstanceOf(DomainValidationException.class);
        assertThat(thrown(() -> service.confirm(GUEST, null, "tok"))).isInstanceOf(DomainValidationException.class);
        assertThat(thrown(() -> service.confirm(null, id, "tok"))).isInstanceOf(DomainValidationException.class);
    }
}
