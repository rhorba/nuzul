package ma.nuzul.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.inventory.InventoryReservations;
import ma.nuzul.payment.ChargeResult;
import ma.nuzul.payment.Payments;
import ma.nuzul.reservation.application.CancelReservationUseCase.Cancelled;
import ma.nuzul.reservation.application.CancelReservationUseCase.RefundStatus;
import ma.nuzul.reservation.domain.CancellationPolicy;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import ma.nuzul.shared.error.CodedException;
import ma.nuzul.shared.error.ConflictException;
import ma.nuzul.shared.error.DomainValidationException;
import ma.nuzul.shared.error.GoneException;
import ma.nuzul.shared.error.NotFoundException;
import org.junit.jupiter.api.Test;

/** Every cancel branch against in-memory reservations, inventory and payments; no Spring, no transactions. */
class CancelServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String GUEST = "guest-1";
    private static final BigDecimal TOTAL = new BigDecimal("1400.00");
    private static final CancellationPolicy FREE = new CancellationPolicy(true, NOW.plusSeconds(3600));
    private static final CancellationPolicy PAST_DEADLINE = new CancellationPolicy(true, NOW);

    private final Map<UUID, Reservation> rows = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final UUID paymentId = UUID.randomUUID();
    /** Status changes another actor makes just before our conditional update (simulates lost races). */
    private final Deque<ReservationStatus> raceBeforeUpdate = new ArrayDeque<>();

    private boolean captured = true;
    private Boolean refundDone; // null = no refund
    private boolean providerFails;

    private final ReservationRepositoryPort reservations = new ReservationRepositoryPort() {
        @Override
        public boolean insertIfKeyAbsent(Reservation reservation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void lockGuestHolds(String guestSub) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countActiveHolds(String guestSub, java.time.Instant now) {
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
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancelIf(UUID id, ReservationStatus from) {
            if (!raceBeforeUpdate.isEmpty()) {
                rows.put(id, withStatus(rows.get(id), raceBeforeUpdate.poll()));
            }
            Reservation r = rows.get(id);
            if (r.status() != from) {
                return false;
            }
            rows.put(id, withStatus(r, ReservationStatus.CANCELLED));
            return true;
        }

        @Override
        public java.util.List<UUID> expiredHoldIds(java.time.Instant now, int limit) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public java.util.Optional<Reservation> lockExpiredHold(UUID id, java.time.Instant now) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public boolean expireIfHeld(UUID id) {
            throw new UnsupportedOperationException("not used here");
        }
    };

    private final InventoryReservations inventory = new InventoryReservations() {
        @Override
        public void reserve(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
            calls.add("release " + rooms);
        }
    };

    private final Payments payments = new Payments() {
        @Override
        public Active begin(UUID reservationId, BigDecimal amountMad) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChargeResult charge(UUID id, String paymentToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean capture(UUID id, String providerRef) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean refund(UUID id, String providerRef) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UUID> claimRefund(UUID reservationId) {
            calls.add("claim");
            if (!captured) {
                return Optional.empty();
            }
            refundDone = false;
            return Optional.of(paymentId);
        }

        @Override
        public void finishRefund(UUID id) {
            calls.add("provider refund");
            if (providerFails) {
                throw new IllegalStateException("provider timeout");
            }
            refundDone = true;
        }

        @Override
        public Optional<Refund> refundOf(UUID reservationId) {
            return refundDone == null ? Optional.empty() : Optional.of(new Refund(paymentId, TOTAL, refundDone));
        }
    };

    private final CancelService service =
            new CancelService(reservations, new CancelTransaction(reservations, inventory, payments, CLOCK), payments);

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

    private UUID reservation(ReservationStatus status, CancellationPolicy policy) {
        UUID id = UUID.randomUUID();
        rows.put(
                id,
                new Reservation(
                        id,
                        GUEST,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        LocalDate.of(2026, 12, 10),
                        LocalDate.of(2026, 12, 12),
                        2,
                        status,
                        TOTAL,
                        NOW.plusSeconds(600),
                        UUID.randomUUID(),
                        "Amina",
                        "a@example.ma",
                        policy));
        return id;
    }

    private static CodedException thrown(Runnable call) {
        Throwable t = catchThrowable(call::run);
        assertThat(t).isInstanceOf(CodedException.class);
        return (CodedException) t;
    }

    @Test
    void aConfirmedStayInsideItsWindowIsReleasedAndRefunded() {
        UUID id = reservation(ReservationStatus.CONFIRMED, FREE);

        Cancelled c = service.cancel(GUEST, id);

        assertThat(c).isEqualTo(new Cancelled(id, ReservationStatus.CANCELLED, RefundStatus.COMPLETED, TOTAL));
        assertThat(calls).containsExactly("release 2", "claim", "provider refund");
        assertThat(rows.get(id).status()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void pastTheDeadlineOrNonRefundableIsReleasedWithoutRefund() {
        UUID late = reservation(ReservationStatus.CONFIRMED, PAST_DEADLINE);
        UUID nonRefundable = reservation(ReservationStatus.CONFIRMED, CancellationPolicy.NON_REFUNDABLE);

        assertThat(service.cancel(GUEST, late).refund()).isEqualTo(RefundStatus.NONE);
        assertThat(service.cancel(GUEST, nonRefundable).refundMad()).isEqualByComparingTo("0.00");
        assertThat(calls).containsExactly("release 2", "release 2");
    }

    @Test
    void aHeldHoldIsReleasedWithNothingToRefund() {
        UUID id = reservation(ReservationStatus.HELD, FREE);

        assertThat(service.cancel(GUEST, id).refund()).isEqualTo(RefundStatus.NONE);
        assertThat(calls).containsExactly("release 2");
    }

    @Test
    void aProviderErrorStillCancelsAndTheRetryFinishesTheRefund() {
        UUID id = reservation(ReservationStatus.CONFIRMED, FREE);
        providerFails = true;

        assertThat(service.cancel(GUEST, id).refund()).isEqualTo(RefundStatus.PENDING);
        assertThat(rows.get(id).status()).isEqualTo(ReservationStatus.CANCELLED);

        providerFails = false;
        assertThat(service.cancel(GUEST, id).refund()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(calls).containsExactly("release 2", "claim", "provider refund", "provider refund");
    }

    @Test
    void cancellingAgainAnswersTheSameWithoutReleasingTwice() {
        UUID id = reservation(ReservationStatus.CONFIRMED, FREE);
        Cancelled first = service.cancel(GUEST, id);

        assertThat(service.cancel(GUEST, id)).isEqualTo(first);
        assertThat(calls).containsExactly("release 2", "claim", "provider refund");
    }

    @Test
    void aConfirmedStayWithoutACapturedPaymentHasNothingToRefund() {
        UUID id = reservation(ReservationStatus.CONFIRMED, FREE);
        captured = false;

        assertThat(service.cancel(GUEST, id).refund()).isEqualTo(RefundStatus.NONE);
    }

    @Test
    void aLostRaceIsDecidedOnTheWinnersStatus() {
        UUID cancelledMeanwhile = reservation(ReservationStatus.CONFIRMED, FREE);
        raceBeforeUpdate.add(ReservationStatus.CANCELLED);
        assertThat(service.cancel(GUEST, cancelledMeanwhile).status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(calls).as("the winner released, not us").isEmpty();

        UUID confirmedMeanwhile = reservation(ReservationStatus.HELD, FREE);
        raceBeforeUpdate.add(ReservationStatus.CONFIRMED);
        assertThat(service.cancel(GUEST, confirmedMeanwhile).refund()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(calls).containsExactly("release 2", "claim", "provider refund");

        UUID expiredMeanwhile = reservation(ReservationStatus.HELD, FREE);
        raceBeforeUpdate.add(ReservationStatus.EXPIRED);
        assertThat(thrown(() -> service.cancel(GUEST, expiredMeanwhile))).isInstanceOf(GoneException.class);
    }

    @Test
    void twoLostRacesInOneCallAskForARetry() {
        UUID id = reservation(ReservationStatus.HELD, FREE);
        raceBeforeUpdate.add(ReservationStatus.CONFIRMED);
        raceBeforeUpdate.add(ReservationStatus.HELD);

        var e = thrown(() -> service.cancel(GUEST, id));

        assertThat(e).isInstanceOf(ConflictException.class);
        assertThat(e.code()).isEqualTo(CancelService.CONCURRENT_UPDATE);
    }

    @Test
    void expiredIs410AndAnotherGuestsIs404() {
        UUID expired = reservation(ReservationStatus.EXPIRED, FREE);
        UUID mine = reservation(ReservationStatus.CONFIRMED, FREE);

        assertThat(thrown(() -> service.cancel(GUEST, expired)).code()).isEqualTo(CancelService.HOLD_EXPIRED);
        var e = thrown(() -> service.cancel("intruder", mine));
        assertThat(e).isInstanceOf(NotFoundException.class);
        assertThat(e.code()).isEqualTo(CancelService.RESERVATION_NOT_FOUND);
        assertThat(thrown(() -> service.cancel(GUEST, null))).isInstanceOf(DomainValidationException.class);
        assertThat(calls).isEmpty();
    }
}
