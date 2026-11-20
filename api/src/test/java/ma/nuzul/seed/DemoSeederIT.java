package ma.nuzul.seed;

import static ma.nuzul.support.AdminApi.ownerSub;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.catalog.application.ManageHotelsUseCase;
import ma.nuzul.catalog.application.ManageRoomTypesUseCase;
import ma.nuzul.inventory.application.ManageInventoryUseCase;
import ma.nuzul.inventory.application.ManageRatesUseCase;
import ma.nuzul.inventory.domain.BookingWindow;
import ma.nuzul.inventory.domain.InventoryChange;
import ma.nuzul.seed.DemoSeeder.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Story 1.5: the demo seed through the real admin use cases and Postgres. Each test seeds under a fresh owner sub, so
 * it is isolated from other tests without cleanup. The clock is a settable one (noon, Casablanca, real current date)
 * so a test can roll the booking window forward a day for both the seeder and the services.
 */
@Import({TestcontainersConfiguration.class, DemoSeederIT.SettableClockConfig.class})
@SpringBootTest
class DemoSeederIT {

    static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    static final LocalDate TODAY = LocalDate.now(ZONE);
    static final int ROOM_TYPES = 180;

    static final class SettableClock extends Clock {
        private volatile Instant instant;

        void set(LocalDate day) {
            instant = day.atTime(LocalTime.NOON).atZone(ZONE).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SettableClockConfig {
        @Bean
        @Primary
        SettableClock settableClock() {
            SettableClock clock = new SettableClock();
            clock.set(TODAY);
            return clock;
        }
    }

    @Autowired
    SettableClock clock;

    @Autowired
    ManageHotelsUseCase hotels;

    @Autowired
    ManageRoomTypesUseCase roomTypes;

    @Autowired
    ManageInventoryUseCase inventory;

    @Autowired
    ManageRatesUseCase rates;

    @Autowired
    JdbcTemplate jdbc;

    private final String owner = ownerSub("seed");

    private DemoSeeder seeder;

    @BeforeEach
    void setUp() {
        clock.set(TODAY);
        seeder = new DemoSeeder(hotels, roomTypes, inventory, rates, clock, owner);
    }

    @Autowired
    ApplicationContext context;

    @Test
    void offByDefault() {
        assertThat(context.getBeanProvider(DemoSeeder.class).getIfAvailable()).isNull();
    }

    @Test
    void seedsSixCitiesSixtyHotelsAndAFullPricedWindowThenARerunAddsNothing() {
        assertThat(seeder.seed()).isEqualTo(new Result(60, ROOM_TYPES, ROOM_TYPES * BookingWindow.DAYS));

        assertThat(count("SELECT count(*) FROM hotel WHERE owner_sub = ?")).isEqualTo(60);
        assertThat(count("SELECT count(DISTINCT city) FROM hotel WHERE owner_sub = ?"))
                .isEqualTo(6);
        assertThat(count("SELECT count(*) FROM hotel WHERE owner_sub = ? AND status <> 'ACTIVE'"))
                .isZero();
        assertThat(count("""
                        SELECT count(*) FROM room_type r JOIN hotel h ON h.id = r.hotel_id WHERE h.owner_sub = ?
                        """)).isEqualTo(ROOM_TYPES);
        assertThat(nightsPerRoomType("room_inventory")).containsOnly((long) BookingWindow.DAYS);
        assertThat(nightsPerRoomType("rate")).containsOnly((long) BookingWindow.DAYS);
        assertThat(count("""
                        SELECT count(*) FROM room_inventory i
                        JOIN room_type r ON r.id = i.room_type_id JOIN hotel h ON h.id = r.hotel_id
                        LEFT JOIN rate p ON p.room_type_id = i.room_type_id AND p.night = i.night
                        WHERE h.owner_sub = ? AND (p.night IS NULL OR i.reserved <> 0 OR i.total <= 0)
                        """))
                .as("every stocked night is sellable and has a price")
                .isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT min(i.night) FROM room_inventory i
                        JOIN room_type r ON r.id = i.room_type_id JOIN hotel h ON h.id = r.hotel_id
                        WHERE h.owner_sub = ?
                        """, LocalDate.class, owner)).isEqualTo(TODAY);

        assertThat(seeder.seed()).isEqualTo(new Result(0, 0, 0));
        assertThat(count("SELECT count(*) FROM hotel WHERE owner_sub = ?")).isEqualTo(60);
    }

    @Test
    void nextDayStocksOnlyTheNightTheWindowRolledOntoAndKeepsAdminEdits() throws Exception {
        seeder.run(null);
        UUID roomTypeId = jdbc.queryForObject("""
                SELECT r.id FROM room_type r JOIN hotel h ON h.id = r.hotel_id
                WHERE h.owner_sub = ? ORDER BY h.name, r.name LIMIT 1
                """, UUID.class, owner);
        LocalDate edited = TODAY.plusDays(10);
        inventory.setTotals(owner, roomTypeId, List.of(new InventoryChange(edited, 1)));

        clock.set(TODAY.plusDays(1));

        assertThat(seeder.seed()).isEqualTo(new Result(0, 0, ROOM_TYPES));
        assertThat(jdbc.queryForObject(
                        "SELECT total FROM room_inventory WHERE room_type_id = ? AND night = ?",
                        Integer.class,
                        roomTypeId,
                        edited))
                .isEqualTo(1);
        LocalDate newNight = TODAY.plusDays(BookingWindow.DAYS);
        assertThat(count("""
                        SELECT count(*) FROM rate p
                        JOIN room_type r ON r.id = p.room_type_id JOIN hotel h ON h.id = r.hotel_id
                        WHERE h.owner_sub = ? AND p.night = '%s'
                        """.formatted(newNight))).isEqualTo(ROOM_TYPES);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, owner);
    }

    private List<Long> nightsPerRoomType(String table) {
        return jdbc.queryForList("""
                SELECT count(t.night) FROM room_type r JOIN hotel h ON h.id = r.hotel_id
                LEFT JOIN %s t ON t.room_type_id = r.id AND t.night >= ? AND t.night < ?
                WHERE h.owner_sub = ? GROUP BY r.id
                """.formatted(table), Long.class, TODAY, TODAY.plusDays(BookingWindow.DAYS), owner);
    }
}
