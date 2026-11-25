package ma.nuzul.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ma.nuzul.catalog.RoomTypeOwnership;
import ma.nuzul.inventory.HotelPricesChanged;
import ma.nuzul.inventory.domain.BookingWindow;
import ma.nuzul.inventory.domain.NightBatch;
import ma.nuzul.inventory.domain.RateChange;
import ma.nuzul.shared.error.NotFoundException;
import ma.nuzul.shared.outbox.Aggregate;
import ma.nuzul.shared.outbox.OutboxPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same order as inventory: batch shape, ownership (through catalog's published API, before any write), booking
 * window, then the writes. Every check runs before the writes, so a refusal writes nothing. The writes are one
 * {@code HotelPricesChanged} event and one upsert in the same transaction: the search index hears of exactly the
 * prices that committed. Inventory counts emit nothing: they do not change a hotel's cheapest price.
 */
@Service
@Transactional
class RateAdminService implements ManageRatesUseCase {

    static final String ROOM_TYPE_NOT_FOUND = InventoryAdminService.ROOM_TYPE_NOT_FOUND;

    private final RateRepositoryPort rates;
    private final RoomTypeOwnership ownership;
    private final OutboxPort outbox;
    private final Clock clock;

    RateAdminService(RateRepositoryPort rates, RoomTypeOwnership ownership, OutboxPort outbox, Clock clock) {
        this.rates = rates;
        this.ownership = ownership;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void setRates(String ownerSub, UUID roomTypeId, List<RateChange> changes) {
        List<LocalDate> nights = NightBatch.requireValid(changes, RateChange::night);
        // One owner-scoped query answers both "may this caller write?" and "which hotel's event?".
        UUID hotelId = ownership
                .hotelOfOwned(roomTypeId, ownerSub)
                .orElseThrow(
                        () -> new NotFoundException(ROOM_TYPE_NOT_FOUND, "Room type " + roomTypeId + " not found"));
        new BookingWindow(LocalDate.now(clock)).requireContains(nights);

        // Keyed by hotel like every catalog event (one partition per hotel); the indexer re-reads, order is not
        // load-bearing.
        outbox.append(
                Aggregate.HOTEL, hotelId, HotelPricesChanged.TYPE, new HotelPricesChanged(hotelId, Instant.now(clock)));
        rates.upsertRates(roomTypeId, changes);
    }
}
