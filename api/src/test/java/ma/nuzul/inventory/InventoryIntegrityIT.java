package ma.nuzul.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the V1 schema itself refuses bad writes (raw SQL, no application code in the way) and
 * that pg_partman partitions room_inventory monthly. Each test runs in its own rolled-back
 * transaction, and makes at most one violating statement (a failed statement aborts the tx).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class InventoryIntegrityIT {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String DEFAULT_PARTITION = "room_inventory_default";

    @Autowired
    JdbcTemplate jdbc;

    private UUID hotelId;
    private UUID roomTypeId;

    @BeforeEach
    void seedHotelAndRoomType() {
        hotelId = UUID.randomUUID();
        roomTypeId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO hotel (id, owner_sub, name, city) VALUES (?, 'owner-1', 'Riad Test', 'Marrakech')",
                hotelId);
        jdbc.update(
                "INSERT INTO room_type (id, hotel_id, name, capacity) VALUES (?, ?, 'Double', 2)", roomTypeId, hotelId);
    }

    @Test
    void oversellingAnInventoryNightInTheCurrentMonthIsRefusedByInventoryNoOversell() {
        jdbc.update("INSERT INTO room_inventory (room_type_id, night, total) VALUES (?, CURRENT_DATE, 2)", roomTypeId);
        assertThat(partitionOf("CURRENT_DATE")).startsWith("room_inventory_p").isNotEqualTo(DEFAULT_PARTITION);

        Throwable thrown = catchThrowable(() -> jdbc.update(
                "UPDATE room_inventory SET reserved = 3 WHERE room_type_id = ? AND night = CURRENT_DATE", roomTypeId));

        assertRefused(thrown, CHECK_VIOLATION, "inventory_no_oversell");
    }

    @Test
    void negativeReservedCountIsRefusedByInventoryNoOversell() {
        jdbc.update("INSERT INTO room_inventory (room_type_id, night, total) VALUES (?, CURRENT_DATE, 2)", roomTypeId);

        Throwable thrown = catchThrowable(() -> jdbc.update(
                "UPDATE room_inventory SET reserved = -1 WHERE room_type_id = ? AND night = CURRENT_DATE", roomTypeId));

        assertRefused(thrown, CHECK_VIOLATION, "inventory_no_oversell");
    }

    @Test
    void reservedEqualToTotalIsAccepted() {
        jdbc.update(
                "INSERT INTO room_inventory (room_type_id, night, total, reserved) VALUES (?, CURRENT_DATE, 2, 2)",
                roomTypeId);

        Integer reserved = jdbc.queryForObject(
                "SELECT reserved FROM room_inventory WHERE room_type_id = ? AND night = CURRENT_DATE",
                Integer.class,
                roomTypeId);
        assertThat(reserved).isEqualTo(2);
    }

    @Test
    void reservationWithCheckOutEqualToCheckInIsRefused() {
        Throwable thrown = catchThrowable(() ->
                insertReservation("HELD", "CURRENT_DATE + 10", "CURRENT_DATE + 10", "guest-1", UUID.randomUUID()));

        assertRefused(thrown, CHECK_VIOLATION, "reservation_half_open_dates");
    }

    @Test
    void reservationWithCheckOutBeforeCheckInIsRefused() {
        Throwable thrown = catchThrowable(
                () -> insertReservation("HELD", "CURRENT_DATE + 10", "CURRENT_DATE + 9", "guest-1", UUID.randomUUID()));

        assertRefused(thrown, CHECK_VIOLATION, "reservation_half_open_dates");
    }

    @Test
    void secondReservationWithSameGuestAndIdempotencyKeyIsRefusedByReservationIdem() {
        UUID key = UUID.randomUUID();
        insertReservation("HELD", "CURRENT_DATE + 10", "CURRENT_DATE + 12", "guest-1", key);

        Throwable thrown = catchThrowable(
                () -> insertReservation("HELD", "CURRENT_DATE + 20", "CURRENT_DATE + 21", "guest-1", key));

        assertRefused(thrown, UNIQUE_VIOLATION, "reservation_idem");
    }

    @Test
    void reservationWithUnknownStatusPendingIsRefused() {
        Throwable thrown = catchThrowable(() ->
                insertReservation("PENDING", "CURRENT_DATE + 10", "CURRENT_DATE + 12", "guest-1", UUID.randomUUID()));

        assertRefused(thrown, CHECK_VIOLATION, "reservation_status_check");
    }

    @Test
    void pgPartmanBackgroundWorkerIsLoadedForThisDatabase() {
        assertThat(jdbc.queryForObject("SHOW shared_preload_libraries", String.class))
                .contains("pg_partman_bgw");
        assertThat(jdbc.queryForObject("SHOW pg_partman_bgw.dbname", String.class))
                .isEqualTo(jdbc.queryForObject("SELECT current_database()", String.class));
    }

    @Test
    void pgPartmanManagesRoomInventoryAsMonthlyPartitionsWithDefault() {
        Boolean monthly = jdbc.queryForObject(
                "SELECT partition_interval::interval = interval '1 month' FROM partman.part_config"
                        + " WHERE parent_table = 'public.room_inventory'",
                Boolean.class);
        assertThat(monthly).isTrue();

        Integer monthlyChildren = jdbc.queryForObject(
                "SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                        + " WHERE i.inhparent = 'public.room_inventory'::regclass AND c.relname <> ?",
                Integer.class,
                DEFAULT_PARTITION);
        assertThat(monthlyChildren).isGreaterThanOrEqualTo(19);

        Integer defaults = jdbc.queryForObject(
                "SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                        + " WHERE i.inhparent = 'public.room_inventory'::regclass AND c.relname = ?",
                Integer.class,
                DEFAULT_PARTITION);
        assertThat(defaults).isEqualTo(1);
    }

    @Test
    void nightFiveYearsAheadLandsInDefaultPartitionAndOversellIsStillRefused() {
        String night = "(CURRENT_DATE + interval '5 years')::date";
        jdbc.update("INSERT INTO room_inventory (room_type_id, night, total) VALUES (?, " + night + ", 1)", roomTypeId);
        assertThat(partitionOf(night)).isEqualTo(DEFAULT_PARTITION);

        Throwable thrown = catchThrowable(() -> jdbc.update(
                "UPDATE room_inventory SET reserved = 2 WHERE room_type_id = ? AND night = " + night, roomTypeId));

        assertRefused(thrown, CHECK_VIOLATION, "inventory_no_oversell");
    }

    /** {@code nightExpr} is a trusted SQL literal from this class, never user input. */
    private String partitionOf(String nightExpr) {
        return jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM room_inventory WHERE room_type_id = ? AND night = " + nightExpr,
                String.class,
                roomTypeId);
    }

    /** Date arguments are trusted SQL expressions from this class, never user input. */
    private void insertReservation(String status, String checkIn, String checkOut, String guestSub, UUID key) {
        jdbc.update(
                "INSERT INTO reservation (id, guest_sub, hotel_id, room_type_id, check_in, check_out, rooms,"
                        + " status, total_mad, idempotency_key, guest_name, guest_email)"
                        + " VALUES (?, ?, ?, ?, " + checkIn + ", " + checkOut + ", 1, ?, 1200.00, ?,"
                        + " 'Test Guest', 'guest@example.test')",
                UUID.randomUUID(),
                guestSub,
                hotelId,
                roomTypeId,
                status,
                key);
    }

    private static void assertRefused(Throwable thrown, String sqlState, String constraint) {
        assertThat(thrown).as("the database must refuse the write").isNotNull();
        Throwable root = NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(root).isInstanceOf(PSQLException.class);
        PSQLException psql = (PSQLException) root;
        assertThat(psql.getSQLState()).isEqualTo(sqlState);
        assertThat(psql.getServerErrorMessage()).isNotNull();
        assertThat(psql.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
    }
}
