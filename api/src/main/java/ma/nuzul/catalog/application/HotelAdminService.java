package ma.nuzul.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import ma.nuzul.catalog.HotelChanged;
import ma.nuzul.catalog.domain.Hotel;
import ma.nuzul.catalog.domain.HotelStatus;
import ma.nuzul.shared.error.NotFoundException;
import ma.nuzul.shared.outbox.Aggregate;
import ma.nuzul.shared.outbox.OutboxPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write appends a {@code HotelChanged} event in its own transaction (class-level {@code @Transactional}), so the
 * search index hears of exactly the changes that committed. A status change goes through update and is covered too.
 */
@Service
@Transactional
class HotelAdminService implements ManageHotelsUseCase {

    static final String HOTEL_NOT_FOUND = "HOTEL_NOT_FOUND";

    private final HotelRepositoryPort hotels;
    private final OutboxPort outbox;
    private final Clock clock;

    HotelAdminService(HotelRepositoryPort hotels, OutboxPort outbox, Clock clock) {
        this.hotels = hotels;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public Hotel create(String ownerSub, HotelDetails d) {
        // Postgres keeps microseconds; truncating keeps the returned value equal to the stored one.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        return changed(hotels.save(new Hotel(
                UUID.randomUUID(),
                ownerSub,
                d.name(),
                d.city(),
                d.address(),
                d.stars(),
                d.amenities(),
                d.lat(),
                d.lon(),
                HotelStatus.ACTIVE,
                now,
                null)));
    }

    @Override
    @Transactional(readOnly = true)
    public Hotel get(String ownerSub, UUID hotelId) {
        return hotels.findByIdAndOwner(hotelId, ownerSub).orElseThrow(() -> notFound(hotelId));
    }

    @Override
    public Hotel update(String ownerSub, UUID hotelId, HotelDetails d, HotelStatus status) {
        Hotel current = get(ownerSub, hotelId);
        return changed(hotels.save(new Hotel(
                current.id(),
                current.ownerSub(),
                d.name(),
                d.city(),
                d.address(),
                d.stars(),
                d.amenities(),
                d.lat(),
                d.lon(),
                status,
                current.createdAt(),
                current.version())));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Hotel> list(String ownerSub, int page, int size) {
        return hotels.findAllByOwner(ownerSub, page, size);
    }

    /** Appends the event for a hotel that was just saved, and hands it back. */
    private Hotel changed(Hotel saved) {
        outbox.append(Aggregate.HOTEL, saved.id(), HotelChanged.TYPE, new HotelChanged(saved.id(), Instant.now(clock)));
        return saved;
    }

    private static NotFoundException notFound(UUID hotelId) {
        return new NotFoundException(HOTEL_NOT_FOUND, "Hotel " + hotelId + " not found");
    }
}
