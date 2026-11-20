package ma.nuzul.seed;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ma.nuzul.catalog.application.HotelDetails;
import ma.nuzul.catalog.application.ManageHotelsUseCase;
import ma.nuzul.catalog.application.ManageRoomTypesUseCase;
import ma.nuzul.catalog.application.PageResult;
import ma.nuzul.catalog.application.RoomTypeDetails;
import ma.nuzul.catalog.domain.Hotel;
import ma.nuzul.catalog.domain.RoomType;
import ma.nuzul.inventory.application.ManageInventoryUseCase;
import ma.nuzul.inventory.application.ManageRatesUseCase;
import ma.nuzul.inventory.domain.BookingWindow;
import ma.nuzul.inventory.domain.InventoryChange;
import ma.nuzul.inventory.domain.RateChange;
import ma.nuzul.seed.SeedCatalog.SeedHotel;
import ma.nuzul.seed.SeedCatalog.SeedRoomType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Loads {@link SeedCatalog} at startup when {@code app.seed.enabled=true}. It acts as the seed owner (a hotel admin)
 * through the same admin use cases as the API, so ownership, validation, the booking window, ascending lock order and
 * the {@code reserved <= total} guard all still apply.
 *
 * <p>Idempotent: hotels and room types are matched by name, and only nights of the window that have no inventory row
 * yet are stocked. A restart therefore adds nothing except the nights the window has rolled onto, and never
 * touches a night that already has inventory, so totals and prices an admin has set stay as they are. On new nights the rate is written before the total, so a night
 * never has rooms without a price.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    private static final int PAGE_SIZE = 100;

    private final ManageHotelsUseCase hotels;
    private final ManageRoomTypesUseCase roomTypes;
    private final ManageInventoryUseCase inventory;
    private final ManageRatesUseCase rates;
    private final Clock clock;
    private final String ownerSub;

    DemoSeeder(
            ManageHotelsUseCase hotels,
            ManageRoomTypesUseCase roomTypes,
            ManageInventoryUseCase inventory,
            ManageRatesUseCase rates,
            Clock clock,
            @Value("${app.seed.owner-sub}") String ownerSub) {
        this.hotels = hotels;
        this.roomTypes = roomTypes;
        this.inventory = inventory;
        this.rates = rates;
        this.clock = clock;
        this.ownerSub = ownerSub;
    }

    @Override
    public void run(ApplicationArguments args) {
        /* Demo data must never stop the API from starting. A failure (e.g. the window rolled past midnight mid-run,
         * so a night is now outside it) leaves what was written; the next start fills the rest. Single instance
         * only: hotels are matched by name, so two replicas seeding at once would both create them. */
        try {
            seed();
        } catch (RuntimeException e) {
            log.error("Demo seed incomplete; it resumes on the next start", e);
        }
    }

    /** @return what this run added */
    Result seed() {
        Map<String, Hotel> existing = existingHotels();
        int hotelsCreated = 0;
        int roomTypesCreated = 0;
        int nightsStocked = 0;

        for (SeedHotel seed : SeedCatalog.hotels()) {
            Hotel hotel = existing.get(key(seed.city(), seed.name()));
            if (hotel == null) {
                hotel = hotels.create(ownerSub, details(seed));
                hotelsCreated++;
            }
            Map<String, RoomType> types = new HashMap<>();
            roomTypes.list(ownerSub, hotel.id()).forEach(t -> types.put(t.name(), t));
            for (SeedRoomType seedType : seed.roomTypes()) {
                RoomType type = types.get(seedType.name());
                if (type == null) {
                    type = roomTypes.create(
                            ownerSub,
                            hotel.id(),
                            new RoomTypeDetails(seedType.name(), seedType.capacity(), seedType.bedConfig()));
                    roomTypesCreated++;
                }
                nightsStocked += stockMissingNights(type.id(), seedType);
            }
        }
        Result result = new Result(hotelsCreated, roomTypesCreated, nightsStocked);
        log.info(
                "Demo seed: {} hotels and {} room types created, {} room-type nights stocked",
                result.hotelsCreated(),
                result.roomTypesCreated(),
                result.nightsStocked());
        return result;
    }

    record Result(int hotelsCreated, int roomTypesCreated, int nightsStocked) {}

    private int stockMissingNights(UUID roomTypeId, SeedRoomType seed) {
        Set<LocalDate> stocked = inventory.stockedNights(ownerSub, roomTypeId);
        BookingWindow window = new BookingWindow(LocalDate.now(clock));
        List<LocalDate> missing = window.today()
                .datesUntil(window.endExclusive())
                .filter(night -> !stocked.contains(night))
                .toList();
        if (missing.isEmpty()) {
            return 0;
        }
        rates.setRates(
                ownerSub,
                roomTypeId,
                missing.stream()
                        .map(n -> new RateChange(n, seed.priceOn(n), seed.refundable()))
                        .toList());
        inventory.setTotals(
                ownerSub,
                roomTypeId,
                missing.stream().map(n -> new InventoryChange(n, seed.rooms())).toList());
        return missing.size();
    }

    private Map<String, Hotel> existingHotels() {
        Map<String, Hotel> byKey = new HashMap<>();
        int page = 0;
        PageResult<Hotel> result;
        do {
            result = hotels.list(ownerSub, page++, PAGE_SIZE);
            result.items().forEach(h -> byKey.put(key(h.city(), h.name()), h));
        } while (!result.items().isEmpty() && (long) page * PAGE_SIZE < result.total());
        return byKey;
    }

    private static String key(String city, String name) {
        return city + "|" + name;
    }

    private static HotelDetails details(SeedHotel seed) {
        return new HotelDetails(
                seed.name(), seed.city(), seed.address(), seed.stars(), seed.amenities(), seed.lat(), seed.lon());
    }
}
