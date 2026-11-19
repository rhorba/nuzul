package ma.nuzul.catalog.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import ma.nuzul.catalog.domain.Hotel;
import ma.nuzul.catalog.domain.HotelStatus;
import ma.nuzul.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class HotelAdminService implements ManageHotelsUseCase {

    static final String HOTEL_NOT_FOUND = "HOTEL_NOT_FOUND";

    private final HotelRepositoryPort hotels;

    HotelAdminService(HotelRepositoryPort hotels) {
        this.hotels = hotels;
    }

    @Override
    public Hotel create(String ownerSub, HotelDetails d) {
        // Postgres keeps microseconds; truncating keeps the returned value equal to the stored one.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return hotels.save(new Hotel(
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
                null));
    }

    @Override
    @Transactional(readOnly = true)
    public Hotel get(String ownerSub, UUID hotelId) {
        return hotels.findByIdAndOwner(hotelId, ownerSub).orElseThrow(() -> notFound(hotelId));
    }

    @Override
    public Hotel update(String ownerSub, UUID hotelId, HotelDetails d, HotelStatus status) {
        Hotel current = get(ownerSub, hotelId);
        return hotels.save(new Hotel(
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
                current.version()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Hotel> list(String ownerSub, int page, int size) {
        return hotels.findAllByOwner(ownerSub, page, size);
    }

    private static NotFoundException notFound(UUID hotelId) {
        return new NotFoundException(HOTEL_NOT_FOUND, "Hotel " + hotelId + " not found");
    }
}
