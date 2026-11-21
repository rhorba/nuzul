package ma.nuzul.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;
import java.util.UUID;
import ma.nuzul.inventory.SoldOutException;
import ma.nuzul.shared.error.CodedException;
import ma.nuzul.shared.error.ConflictException;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

/**
 * reserve() rules against the in-memory port; no Spring. The transaction (MANDATORY propagation, rollback of the
 * partial increments) is proved by the ITs.
 */
class InventoryReservationServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 12, 10);

    private final UUID roomTypeId = UUID.randomUUID();
    private final InMemoryInventoryRepository repo = new InMemoryInventoryRepository();
    private final InventoryReservationService service = new InventoryReservationService(repo);

    private void seed(int plusDays, int total, int reserved) {
        repo.seed(roomTypeId, D1.plusDays(plusDays), total, reserved);
    }

    private int reserved(int plusDays) {
        return repo.row(roomTypeId, D1.plusDays(plusDays)).reserved();
    }

    private static CodedException thrown(Runnable call) {
        Throwable t = catchThrowable(call::run);
        assertThat(t).isInstanceOf(CodedException.class);
        return (CodedException) t;
    }

    @Test
    void everyNightAvailableIncrementsEachNightByRooms() {
        seed(0, 5, 0);
        seed(1, 5, 1);
        seed(2, 5, 2);

        service.reserve(roomTypeId, D1, D1.plusDays(3), 3);

        assertThat(reserved(0)).isEqualTo(3);
        assertThat(reserved(1)).isEqualTo(4);
        assertThat(reserved(2)).isEqualTo(5);
    }

    @Test
    void fewerNightsUpdatedThanStayedIsSoldOut() {
        seed(0, 2, 0);
        seed(1, 2, 2);
        seed(2, 2, 0);

        var e = thrown(() -> service.reserve(roomTypeId, D1, D1.plusDays(3), 1));

        assertThat(e).isInstanceOf(SoldOutException.class).isInstanceOf(ConflictException.class);
        assertThat(e.code()).isEqualTo(SoldOutException.SOLD_OUT).isEqualTo("SOLD_OUT");
    }

    @Test
    void aNightWithNoInventoryRowIsSoldOut() {
        seed(0, 2, 0);
        seed(2, 2, 0);

        var e = thrown(() -> service.reserve(roomTypeId, D1, D1.plusDays(3), 1));

        assertThat(e).isInstanceOf(SoldOutException.class);
    }

    @Test
    void unknownRoomTypeIsSoldOut() {
        var e = thrown(() -> service.reserve(UUID.randomUUID(), D1, D1.plusDays(1), 1));

        assertThat(e).isInstanceOf(SoldOutException.class);
    }

    @Test
    void invalidStayIsValidationFailedBeforeAnyWrite() {
        seed(0, 2, 0);

        var e = thrown(() -> service.reserve(roomTypeId, D1, D1, 1));

        assertThat(e).isInstanceOf(DomainValidationException.class);
        assertThat(e.code()).isEqualTo(DomainValidationException.VALIDATION_FAILED);
        assertThat(repo.reserveCalls).isZero();
    }

    @Test
    void roomsOutsideOneToNineIsValidationFailedBeforeAnyWrite() {
        seed(0, 20, 0);

        assertThat(thrown(() -> service.reserve(roomTypeId, D1, D1.plusDays(1), 0)))
                .isInstanceOf(DomainValidationException.class);
        assertThat(thrown(() -> service.reserve(roomTypeId, D1, D1.plusDays(1), 10)))
                .isInstanceOf(DomainValidationException.class);
        assertThat(repo.reserveCalls).isZero();
    }

    @Test
    void nullRoomTypeIsValidationFailed() {
        var e = thrown(() -> service.reserve(null, D1, D1.plusDays(1), 1));

        assertThat(e).isInstanceOf(DomainValidationException.class);
        assertThat(repo.reserveCalls).isZero();
    }

    @Test
    void releaseGivesBackEveryNightOfTheStay() {
        for (int i = 0; i < 3; i++) {
            repo.seed(roomTypeId, D1.plusDays(i), 5, 3);
        }

        service.release(roomTypeId, D1, D1.plusDays(3), 2);

        for (int i = 0; i < 3; i++) {
            assertThat(repo.row(roomTypeId, D1.plusDays(i)).reserved()).isEqualTo(1);
            assertThat(repo.row(roomTypeId, D1.plusDays(i)).total()).isEqualTo(5);
        }
    }

    @Test
    void releasingMoreThanIsReservedOrAMissingNightIsRefused() {
        repo.seed(roomTypeId, D1, 5, 1);
        repo.seed(roomTypeId, D1.plusDays(1), 5, 3);

        assertThat(catchThrowable(() -> service.release(roomTypeId, D1, D1.plusDays(2), 2)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(catchThrowable(() -> service.release(roomTypeId, D1.plusDays(1), D1.plusDays(3), 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releaseValidatesItsInput() {
        assertThat(catchThrowable(() -> service.release(null, D1, D1.plusDays(1), 1)))
                .isInstanceOf(DomainValidationException.class);
        assertThat(catchThrowable(() -> service.release(roomTypeId, D1, D1, 1)))
                .isInstanceOf(DomainValidationException.class);
        assertThat(catchThrowable(() -> service.release(roomTypeId, D1, D1.plusDays(1), 0)))
                .isInstanceOf(DomainValidationException.class);
    }
}
