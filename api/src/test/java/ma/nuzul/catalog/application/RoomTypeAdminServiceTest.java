package ma.nuzul.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import ma.nuzul.catalog.RoomTypeChanged;
import ma.nuzul.catalog.domain.RoomType;
import ma.nuzul.shared.error.NotFoundException;
import ma.nuzul.shared.outbox.Aggregate;
import ma.nuzul.shared.outbox.RecordingOutbox;
import org.junit.jupiter.api.Test;

/** Room-type use-case rules against in-memory ports; no Spring. */
class RoomTypeAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryHotelRepository hotelRepo = new InMemoryHotelRepository();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final InMemoryRoomTypeRepository roomTypeRepo = new InMemoryRoomTypeRepository(hotelRepo);
    private final HotelAdminService hotelService = new HotelAdminService(hotelRepo, outbox, CLOCK);
    private final RoomTypeAdminService service = new RoomTypeAdminService(hotelRepo, roomTypeRepo, outbox, CLOCK);

    private UUID hotelOf(String owner) {
        return hotelService
                .create(owner, new HotelDetails("Riad", "Fes", null, null, List.of(), null, null))
                .id();
    }

    private static RoomTypeDetails details(String name, int capacity) {
        return new RoomTypeDetails(name, capacity, "1 double");
    }

    private static String codeOf(Throwable e) {
        return ((NotFoundException) e).code();
    }

    @Test
    void createAssignsIdAndParentHotel() {
        UUID h = hotelOf("owner-a");

        RoomType r = service.create("owner-a", h, details("Double", 2));

        assertThat(r.id()).isNotNull();
        assertThat(r.hotelId()).isEqualTo(h);
        assertThat(r.name()).isEqualTo("Double");
        assertThat(r.capacity()).isEqualTo(2);
        assertThat(r.bedConfig()).isEqualTo("1 double");
        assertThat(roomTypeRepo.rows).containsKey(r.id());
    }

    @Test
    void createOnForeignOrUnknownHotelIsHotelNotFoundAndStoresNothing() {
        UUID h = hotelOf("owner-a");

        assertThatThrownBy(() -> service.create("owner-b", h, details("Double", 2)))
                .isInstanceOf(NotFoundException.class)
                .extracting(RoomTypeAdminServiceTest::codeOf)
                .isEqualTo("HOTEL_NOT_FOUND");
        assertThatThrownBy(() -> service.create("owner-a", UUID.randomUUID(), details("Double", 2)))
                .isInstanceOf(NotFoundException.class)
                .extracting(RoomTypeAdminServiceTest::codeOf)
                .isEqualTo("HOTEL_NOT_FOUND");
        assertThat(roomTypeRepo.rows).isEmpty();
    }

    @Test
    void listReturnsOnlyThatHotelsRoomTypesOrderedByName() {
        UUID h1 = hotelOf("owner-a");
        UUID h2 = hotelOf("owner-a");
        service.create("owner-a", h1, details("Suite", 4));
        service.create("owner-a", h1, details("Double", 2));
        service.create("owner-a", h2, details("Other", 2));

        assertThat(service.list("owner-a", h1)).extracting(RoomType::name).containsExactly("Double", "Suite");
    }

    @Test
    void listOfForeignHotelIsHotelNotFound() {
        UUID h = hotelOf("owner-a");

        assertThatThrownBy(() -> service.list("owner-b", h))
                .isInstanceOf(NotFoundException.class)
                .extracting(RoomTypeAdminServiceTest::codeOf)
                .isEqualTo("HOTEL_NOT_FOUND");
    }

    @Test
    void updateReplacesDetailsKeepingIdentity() {
        UUID h = hotelOf("owner-a");
        RoomType r = service.create("owner-a", h, details("Double", 2));

        RoomType updated = service.update("owner-a", h, r.id(), new RoomTypeDetails("Twin", 3, null));

        assertThat(updated.id()).isEqualTo(r.id());
        assertThat(updated.hotelId()).isEqualTo(h);
        assertThat(updated.name()).isEqualTo("Twin");
        assertThat(updated.capacity()).isEqualTo(3);
        assertThat(updated.bedConfig()).isNull();
        assertThat(roomTypeRepo.rows.get(r.id())).isEqualTo(updated);
    }

    @Test
    void updateOnForeignHotelIsHotelNotFoundAndLeavesRoomTypeUnchanged() {
        UUID h = hotelOf("owner-a");
        RoomType r = service.create("owner-a", h, details("Double", 2));

        assertThatThrownBy(() -> service.update("owner-b", h, r.id(), details("Hijacked", 9)))
                .isInstanceOf(NotFoundException.class)
                .extracting(RoomTypeAdminServiceTest::codeOf)
                .isEqualTo("HOTEL_NOT_FOUND");
        assertThat(roomTypeRepo.rows.get(r.id())).isEqualTo(r);
    }

    @Test
    void updateUnderTheWrongParentOrUnknownIdIsRoomTypeNotFound() {
        UUID h1 = hotelOf("owner-a");
        UUID h2 = hotelOf("owner-a");
        RoomType r = service.create("owner-a", h1, details("Double", 2));

        assertThatThrownBy(() -> service.update("owner-a", h2, r.id(), details("Moved", 2)))
                .isInstanceOf(NotFoundException.class)
                .extracting(RoomTypeAdminServiceTest::codeOf)
                .isEqualTo("ROOM_TYPE_NOT_FOUND");
        assertThatThrownBy(() -> service.update("owner-a", h1, UUID.randomUUID(), details("Ghost", 2)))
                .isInstanceOf(NotFoundException.class)
                .extracting(RoomTypeAdminServiceTest::codeOf)
                .isEqualTo("ROOM_TYPE_NOT_FOUND");
        assertThat(roomTypeRepo.rows.get(r.id())).isEqualTo(r);
    }

    @Test
    void createAndUpdateEachAppendOneRoomTypeChangedEvent() {
        UUID h = hotelOf("owner-a");
        outbox.events.clear(); // the hotel's own HotelChanged

        RoomType r = service.create("owner-a", h, details("Double", 2));
        service.update("owner-a", h, r.id(), details("Twin", 2));

        var expected = new RecordingOutbox.Event(
                Aggregate.HOTEL, h, RoomTypeChanged.TYPE, new RoomTypeChanged(r.id(), h, NOW));
        assertThat(outbox.events).containsExactly(expected, expected);
    }

    @Test
    void readsAndRefusedWritesAppendNothing() {
        UUID h = hotelOf("owner-a");
        RoomType r = service.create("owner-a", h, details("Double", 2));
        outbox.events.clear();

        service.list("owner-a", h);
        assertThatThrownBy(() -> service.create("owner-b", h, details("Double", 2)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.update("owner-b", h, r.id(), details("Hijacked", 9)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.update("owner-a", h, UUID.randomUUID(), details("Ghost", 2)))
                .isInstanceOf(NotFoundException.class);

        assertThat(outbox.events).isEmpty();
    }
}
