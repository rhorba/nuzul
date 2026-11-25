package ma.nuzul.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import ma.nuzul.catalog.HotelChanged;
import ma.nuzul.catalog.domain.Hotel;
import ma.nuzul.catalog.domain.HotelStatus;
import ma.nuzul.shared.error.NotFoundException;
import ma.nuzul.shared.outbox.Aggregate;
import ma.nuzul.shared.outbox.RecordingOutbox;
import org.junit.jupiter.api.Test;

/** Use-case rules against an in-memory port; no Spring. */
class HotelAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryHotelRepository repo = new InMemoryHotelRepository();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final HotelAdminService service = new HotelAdminService(repo, outbox, CLOCK);

    private static HotelDetails details(String name) {
        return new HotelDetails(
                name, "Marrakech", "Medina", 4, List.of("wifi"), new BigDecimal("31.6"), new BigDecimal("-8.0"));
    }

    @Test
    void createAssignsIdOwnerActiveStatusAndCreatedAt() {
        Hotel h = service.create("owner-a", details("Riad A"));

        assertThat(h.id()).isNotNull();
        assertThat(h.ownerSub()).isEqualTo("owner-a");
        assertThat(h.status()).isEqualTo(HotelStatus.ACTIVE);
        assertThat(h.createdAt()).isEqualTo(NOW);
        assertThat(h.amenities()).containsExactly("wifi");
        assertThat(repo.rows).containsKey(h.id());
    }

    @Test
    void getReturnsOwnHotel() {
        Hotel h = service.create("owner-a", details("Riad A"));

        assertThat(service.get("owner-a", h.id())).isEqualTo(h);
    }

    @Test
    void getOfForeignOrUnknownHotelIsNotFound() {
        Hotel h = service.create("owner-a", details("Riad A"));

        assertThatThrownBy(() -> service.get("owner-b", h.id()))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).code())
                .isEqualTo("HOTEL_NOT_FOUND");
        assertThatThrownBy(() -> service.get("owner-a", UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateReplacesDetailsAndStatusKeepingIdentity() {
        Hotel h = service.create("owner-a", details("Riad A"));

        Hotel updated = service.update("owner-a", h.id(), details("Riad A2"), HotelStatus.INACTIVE);

        assertThat(updated.id()).isEqualTo(h.id());
        assertThat(updated.ownerSub()).isEqualTo("owner-a");
        assertThat(updated.createdAt()).isEqualTo(h.createdAt());
        assertThat(updated.name()).isEqualTo("Riad A2");
        assertThat(updated.status()).isEqualTo(HotelStatus.INACTIVE);
        assertThat(repo.rows.get(h.id()).name()).isEqualTo("Riad A2");
    }

    @Test
    void updateOfForeignHotelIsNotFoundAndLeavesItUnchanged() {
        Hotel h = service.create("owner-a", details("Riad A"));

        assertThatThrownBy(() -> service.update("owner-b", h.id(), details("Hijacked"), HotelStatus.INACTIVE))
                .isInstanceOf(NotFoundException.class);
        assertThat(repo.rows.get(h.id()).name()).isEqualTo("Riad A");
    }

    @Test
    void listReturnsOnlyTheOwnersHotels() {
        service.create("owner-a", details("A1"));
        service.create("owner-b", details("B1"));
        service.create("owner-a", details("A2"));

        PageResult<Hotel> page = service.list("owner-a", 0, 20);

        assertThat(page.items()).extracting(Hotel::ownerSub).containsOnly("owner-a");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
    }

    @Test
    void createAndUpdateEachAppendOneHotelChangedEvent() {
        Hotel h = service.create("owner-a", details("Riad A"));
        service.update("owner-a", h.id(), details("Riad A2"), HotelStatus.INACTIVE);

        assertThat(outbox.events)
                .containsExactly(
                        new RecordingOutbox.Event(
                                Aggregate.HOTEL, h.id(), HotelChanged.TYPE, new HotelChanged(h.id(), NOW)),
                        new RecordingOutbox.Event(
                                Aggregate.HOTEL, h.id(), HotelChanged.TYPE, new HotelChanged(h.id(), NOW)));
    }

    @Test
    void readsAndRefusedUpdatesAppendNothing() {
        Hotel h = service.create("owner-a", details("Riad A"));
        outbox.events.clear();

        service.get("owner-a", h.id());
        service.list("owner-a", 0, 20);
        assertThatThrownBy(() -> service.update("owner-b", h.id(), details("Hijacked"), HotelStatus.INACTIVE))
                .isInstanceOf(NotFoundException.class);

        assertThat(outbox.events).isEmpty();
    }
}
