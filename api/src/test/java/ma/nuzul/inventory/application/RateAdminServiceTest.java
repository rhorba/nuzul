package ma.nuzul.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import ma.nuzul.catalog.RoomTypeOwnership;
import ma.nuzul.inventory.domain.BookingWindow;
import ma.nuzul.inventory.domain.RateChange;
import ma.nuzul.shared.error.CodedException;
import ma.nuzul.shared.error.DomainValidationException;
import ma.nuzul.shared.error.NotFoundException;
import org.junit.jupiter.api.Test;

/** Bulk rate rules against in-memory ports and a fixed clock; no Spring. */
class RateAdminServiceTest {

    /* A fixed offset, not Africa/Casablanca: Morocco's offset differs between JDK tzdata releases (2026). */
    private static final ZoneOffset UTC_PLUS_ONE = ZoneOffset.ofHours(1);
    /* 23:30 UTC on Nov 18 is already Nov 19 at UTC+1: proves "today" comes from the clock's zone. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T23:30:00Z"), UTC_PLUS_ONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final String OWNER = "owner-1";

    private final UUID roomTypeId = UUID.randomUUID();
    private final InMemoryRateRepository repo = new InMemoryRateRepository();
    private final RoomTypeOwnership ownership = (id, sub) -> id.equals(roomTypeId) && sub.equals(OWNER);
    private final RateAdminService service = new RateAdminService(repo, ownership, CLOCK);

    private static RateChange change(int plusDays, String price, boolean refundable) {
        return new RateChange(TODAY.plusDays(plusDays), new BigDecimal(price), refundable);
    }

    private static CodedException thrown(Runnable call) {
        Throwable t = catchThrowable(call::run);
        assertThat(t).isInstanceOf(CodedException.class);
        return (CodedException) t;
    }

    @Test
    void insertsThenUpdatesPriceAndRefundableKeepingFreeCancelHours() {
        repo.seed(roomTypeId, TODAY.plusDays(2), new InMemoryRateRepository.Row(new BigDecimal("500.00"), true, 72));

        service.setRates(OWNER, roomTypeId, List.of(change(1, "850.00", true), change(2, "1234.5", false)));
        service.setRates(OWNER, roomTypeId, List.of(change(1, "900", false)));

        assertThat(repo.row(roomTypeId, TODAY.plusDays(1)))
                .isEqualTo(new InMemoryRateRepository.Row(new BigDecimal("900"), false, 48));
        assertThat(repo.row(roomTypeId, TODAY.plusDays(2)))
                .isEqualTo(new InMemoryRateRepository.Row(new BigDecimal("1234.5"), false, 72));
    }

    @Test
    void otherOwnerIsRoomTypeNotFoundAndNothingIsWritten() {
        var e = thrown(() -> service.setRates("someone-else", roomTypeId, List.of(change(1, "100", true))));

        assertThat(e).isInstanceOf(NotFoundException.class);
        assertThat(e.code()).isEqualTo(RateAdminService.ROOM_TYPE_NOT_FOUND);
        assertThat(repo.upsertCalls).isZero();
    }

    @Test
    void unknownRoomTypeIsRoomTypeNotFound() {
        var e = thrown(() -> service.setRates(OWNER, UUID.randomUUID(), List.of(change(1, "100", true))));

        assertThat(e.code()).isEqualTo(RateAdminService.ROOM_TYPE_NOT_FOUND);
        assertThat(repo.upsertCalls).isZero();
    }

    @Test
    void nightsOutsideTheWindowAreRefusedAndNothingIsWritten() {
        var e = thrown(() -> service.setRates(
                OWNER, roomTypeId, List.of(change(0, "1", true), change(180, "1", true), change(-1, "1", true))));

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
        service.setRates(OWNER, roomTypeId, List.of(change(0, "1", true), change(179, "1", true)));

        assertThat(repo.row(roomTypeId, TODAY)).isNotNull();
        assertThat(repo.row(roomTypeId, TODAY.plusDays(179))).isNotNull();
    }

    @Test
    void invalidBatchShapesAreValidationFailedAndNothingIsWritten() {
        List<RateChange> all181 = new ArrayList<>(
                IntStream.range(0, 180).mapToObj(i -> change(i, "1", true)).toList());
        all181.add(change(1, "2", true));

        assertValidationFailed(null);
        assertValidationFailed(List.of());
        assertValidationFailed(Arrays.asList(change(1, "1", true), null));
        assertValidationFailed(List.of(change(1, "1", true), change(1, "2", false)));
        assertValidationFailed(all181);
    }

    /* Shape is checked before ownership: an invalid batch never reaches the ownership lookup. */
    @Test
    void invalidShapeIsReportedBeforeOwnership() {
        var e = thrown(() -> service.setRates("someone-else", roomTypeId, List.of()));

        assertThat(e.code()).isEqualTo(DomainValidationException.VALIDATION_FAILED);
    }

    private void assertValidationFailed(List<RateChange> changes) {
        var e = thrown(() -> service.setRates(OWNER, roomTypeId, changes));

        assertThat(e).isInstanceOf(DomainValidationException.class);
        assertThat(e.code()).isEqualTo(DomainValidationException.VALIDATION_FAILED);
        assertThat(repo.upsertCalls).isZero();
    }
}
