package ma.nuzul.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import ma.nuzul.catalog.RoomTypeOwnership;
import ma.nuzul.inventory.domain.BookingWindow;
import ma.nuzul.inventory.domain.InventoryChange;
import ma.nuzul.shared.error.CodedException;
import ma.nuzul.shared.error.ConflictException;
import ma.nuzul.shared.error.DomainValidationException;
import ma.nuzul.shared.error.NotFoundException;
import org.junit.jupiter.api.Test;

/** Bulk inventory rules against in-memory ports and a fixed clock; no Spring. */
class InventoryAdminServiceTest {

    /* A fixed offset, not Africa/Casablanca: Morocco's offset differs between JDK tzdata releases (2026). */
    private static final ZoneOffset UTC_PLUS_ONE = ZoneOffset.ofHours(1);
    /* 23:30 UTC on Nov 18 is already Nov 19 at UTC+1: proves "today" comes from the clock's zone. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T23:30:00Z"), UTC_PLUS_ONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final String OWNER = "owner-1";
    private static final UUID HOTEL_ID = UUID.randomUUID();

    private final UUID roomTypeId = UUID.randomUUID();
    private final InMemoryInventoryRepository repo = new InMemoryInventoryRepository();
    private final RoomTypeOwnership ownership =
            (id, sub) -> id.equals(roomTypeId) && sub.equals(OWNER) ? Optional.of(HOTEL_ID) : Optional.empty();
    private final InventoryAdminService service = new InventoryAdminService(repo, ownership, CLOCK);

    private static InventoryChange change(int plusDays, int total) {
        return new InventoryChange(TODAY.plusDays(plusDays), total);
    }

    private static CodedException thrown(Runnable call) {
        Throwable t = catchThrowable(call::run);
        assertThat(t).isInstanceOf(CodedException.class);
        return (CodedException) t;
    }

    @Test
    void insertsThenUpdatesTotals() {
        service.setTotals(OWNER, roomTypeId, List.of(change(1, 5), change(2, 6)));
        service.setTotals(OWNER, roomTypeId, List.of(change(1, 7)));

        assertThat(repo.row(roomTypeId, TODAY.plusDays(1)).total()).isEqualTo(7);
        assertThat(repo.row(roomTypeId, TODAY.plusDays(2)).total()).isEqualTo(6);
    }

    @Test
    void totalBelowReservedIsConflictListingRefusedNightsAscending() {
        repo.seed(roomTypeId, TODAY.plusDays(3), 5, 4);
        repo.seed(roomTypeId, TODAY.plusDays(1), 5, 2);

        var e = thrown(() -> service.setTotals(OWNER, roomTypeId, List.of(change(3, 3), change(2, 9), change(1, 1))));

        assertThat(e).isInstanceOf(ConflictException.class);
        assertThat(e.code()).isEqualTo(InventoryAdminService.INVENTORY_BELOW_RESERVED);
        assertThat(e.properties())
                .containsEntry(
                        "nights",
                        List.of(TODAY.plusDays(1).toString(), TODAY.plusDays(3).toString()));
    }

    @Test
    void totalEqualToReservedIsAccepted() {
        repo.seed(roomTypeId, TODAY.plusDays(1), 5, 3);

        service.setTotals(OWNER, roomTypeId, List.of(change(1, 3)));

        assertThat(repo.row(roomTypeId, TODAY.plusDays(1))).isEqualTo(new InMemoryInventoryRepository.Row(3, 3));
    }

    @Test
    void otherOwnerIsRoomTypeNotFoundAndNothingIsWritten() {
        var e = thrown(() -> service.setTotals("someone-else", roomTypeId, List.of(change(1, 5))));

        assertThat(e).isInstanceOf(NotFoundException.class);
        assertThat(e.code()).isEqualTo(InventoryAdminService.ROOM_TYPE_NOT_FOUND);
        assertThat(repo.upsertCalls).isZero();
    }

    @Test
    void unknownRoomTypeIsRoomTypeNotFound() {
        var e = thrown(() -> service.setTotals(OWNER, UUID.randomUUID(), List.of(change(1, 5))));

        assertThat(e.code()).isEqualTo(InventoryAdminService.ROOM_TYPE_NOT_FOUND);
        assertThat(repo.upsertCalls).isZero();
    }

    @Test
    void nightsOutsideTheWindowAreRefusedAndNothingIsWritten() {
        var e = thrown(
                () -> service.setTotals(OWNER, roomTypeId, List.of(change(0, 1), change(-1, 1), change(180, 1))));

        assertThat(e.code()).isEqualTo(BookingWindow.NIGHT_OUTSIDE_WINDOW);
        assertThat(e.properties())
                .containsEntry(
                        "nights",
                        List.of(
                                TODAY.minusDays(1).toString(),
                                TODAY.plusDays(180).toString()));
        assertThat(repo.upsertCalls).isZero();
    }

    @Test
    void windowEdgesTodayAndTodayPlus179AreAccepted() {
        service.setTotals(OWNER, roomTypeId, List.of(change(0, 1), change(179, 1)));

        assertThat(repo.row(roomTypeId, TODAY)).isNotNull();
        assertThat(repo.row(roomTypeId, TODAY.plusDays(179))).isNotNull();
    }

    @Test
    void emptyOrNullBatchIsValidationFailed() {
        assertValidationFailed(List.of());
        assertValidationFailed(null);
    }

    @Test
    void nullEntryIsValidationFailed() {
        assertValidationFailed(Arrays.asList(change(1, 1), null));
    }

    @Test
    void exactly180EntriesAreAcceptedAnd181AreValidationFailed() {
        List<InventoryChange> changes = new ArrayList<>(
                IntStream.range(0, 180).mapToObj(i -> change(i, 1)).toList());
        service.setTotals(OWNER, roomTypeId, changes);

        changes.add(change(1, 2));
        assertValidationFailed(changes);
    }

    @Test
    void duplicateNightsAreValidationFailed() {
        assertValidationFailed(List.of(change(1, 1), change(1, 2)));
    }

    private void assertValidationFailed(List<InventoryChange> changes) {
        int callsBefore = repo.upsertCalls;
        var e = thrown(() -> service.setTotals(OWNER, roomTypeId, changes));

        assertThat(e).isInstanceOf(DomainValidationException.class);
        assertThat(e.code()).isEqualTo(DomainValidationException.VALIDATION_FAILED);
        assertThat(repo.upsertCalls).isEqualTo(callsBefore);
    }

    @Test
    void stockedNightsListsOnlyNightsInsideTheWindow() {
        repo.seed(roomTypeId, TODAY.minusDays(1), 5, 0);
        repo.seed(roomTypeId, TODAY, 5, 0);
        repo.seed(roomTypeId, TODAY.plusDays(BookingWindow.DAYS - 1), 5, 5);
        repo.seed(roomTypeId, TODAY.plusDays(BookingWindow.DAYS), 5, 0);

        assertThat(service.stockedNights(OWNER, roomTypeId))
                .containsExactlyInAnyOrder(TODAY, TODAY.plusDays(BookingWindow.DAYS - 1));
    }

    @Test
    void stockedNightsOfAnotherOwnersRoomTypeIsNotFound() {
        repo.seed(roomTypeId, TODAY, 5, 0);

        var e = thrown(() -> service.stockedNights("intruder", roomTypeId));

        assertThat(e).isInstanceOf(NotFoundException.class);
        assertThat(e.code()).isEqualTo(InventoryAdminService.ROOM_TYPE_NOT_FOUND);
    }
}
