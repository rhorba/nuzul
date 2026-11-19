package ma.nuzul.catalog.application;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.catalog.domain.Hotel;

/** Test fake for {@link HotelRepositoryPort}; owner scope is part of the lookup, as in the real query. */
class InMemoryHotelRepository implements HotelRepositoryPort {

    final Map<UUID, Hotel> rows = new LinkedHashMap<>();

    @Override
    public Hotel save(Hotel hotel) {
        int next = hotel.version() == null ? 0 : hotel.version() + 1;
        var stored = new Hotel(
                hotel.id(),
                hotel.ownerSub(),
                hotel.name(),
                hotel.city(),
                hotel.address(),
                hotel.stars(),
                hotel.amenities(),
                hotel.lat(),
                hotel.lon(),
                hotel.status(),
                hotel.createdAt(),
                next);
        rows.put(hotel.id(), stored);
        return stored;
    }

    @Override
    public Optional<Hotel> findByIdAndOwner(UUID id, String ownerSub) {
        return Optional.ofNullable(rows.get(id)).filter(h -> h.ownerSub().equals(ownerSub));
    }

    @Override
    public PageResult<Hotel> findAllByOwner(String ownerSub, int page, int size) {
        var mine = rows.values().stream()
                .filter(h -> h.ownerSub().equals(ownerSub))
                .sorted(Comparator.comparing(Hotel::createdAt)
                        .thenComparing(Hotel::id)
                        .reversed())
                .toList();
        var items = mine.stream().skip((long) page * size).limit(size).toList();
        return new PageResult<>(items, page, size, mine.size());
    }
}
