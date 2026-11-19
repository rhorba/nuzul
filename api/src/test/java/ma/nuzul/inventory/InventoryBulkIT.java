package ma.nuzul.inventory;

import static ma.nuzul.support.AdminApi.PROBLEM_JSON;
import static ma.nuzul.support.AdminApi.admin;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static ma.nuzul.support.AdminApi.ownerSub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.support.AdminApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 1.3: owner-scoped bulk inventory upsert, end to end through security, MVC, the guarded SQL and Postgres
 * partitions. The Clock is fixed at noon (Casablanca) of the real current date: deterministic for the whole run,
 * and still "now" so the premade monthly partitions cover the booking window.
 */
@Import({TestcontainersConfiguration.class, InventoryBulkIT.FixedClock.class})
@SpringBootTest
@AutoConfigureMockMvc
class InventoryBulkIT {

    static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    static final LocalDate TODAY = LocalDate.now(ZONE);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atTime(LocalTime.NOON).atZone(ZONE).toInstant(), ZONE);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /* Fresh owner subs per test: isolated from other tests and other IT classes, no FK-sensitive cleanup. */
    private final String ownerA = ownerSub("owner-a");
    private final String ownerB = ownerSub("owner-b");

    private static String inventory(String roomTypeId) {
        return "/api/v1/admin/room-types/" + roomTypeId + "/inventory";
    }

    private static LocalDate day(int plusDays) {
        return TODAY.plusDays(plusDays);
    }

    private static String entry(LocalDate night, int total) {
        return "{\"night\":\"" + night + "\",\"total\":" + total + "}";
    }

    private static String body(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    private String createRoomType(String owner) throws Exception {
        return AdminApi.createRoomType(mvc, owner);
    }

    private void putOk(String roomTypeId, String owner, String body) throws Exception {
        mvc.perform(json(put(inventory(roomTypeId)), body).with(admin(owner))).andExpect(status().isNoContent());
    }

    /** Every row of the room type, ordered by night, as one JSON text (null when there are none). */
    private String snapshot(String roomTypeId) {
        return jdbc.queryForObject(
                "SELECT json_agg(row_to_json(r) ORDER BY r.night)::text FROM room_inventory r"
                        + " WHERE room_type_id = ?::uuid",
                String.class,
                roomTypeId);
    }

    private int rowCount(String roomTypeId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM room_inventory WHERE room_type_id = ?::uuid", Integer.class, roomTypeId);
    }

    private Map<String, Object> row(String roomTypeId, LocalDate night) {
        return jdbc.queryForMap(
                "SELECT total, reserved FROM room_inventory WHERE room_type_id = ?::uuid AND night = ?",
                roomTypeId,
                night);
    }

    private void setReserved(String roomTypeId, LocalDate night, int reserved) {
        jdbc.update(
                "UPDATE room_inventory SET reserved = ? WHERE room_type_id = ?::uuid AND night = ?",
                reserved,
                roomTypeId,
                night);
    }

    @Test
    void insertThenUpdateTheSameNights() throws Exception {
        String rt = createRoomType(ownerA);

        putOk(rt, ownerA, body(entry(day(2), 5), entry(day(1), 4)));
        assertThat(row(rt, day(1))).containsEntry("total", 4).containsEntry("reserved", 0);
        assertThat(row(rt, day(2))).containsEntry("total", 5).containsEntry("reserved", 0);

        setReserved(rt, day(1), 2);
        putOk(rt, ownerA, body(entry(day(1), 9), entry(day(2), 0)));

        assertThat(rowCount(rt)).isEqualTo(2);
        assertThat(row(rt, day(1))).containsEntry("total", 9).containsEntry("reserved", 2);
        assertThat(row(rt, day(2))).containsEntry("total", 0).containsEntry("reserved", 0);
    }

    @Test
    void totalBelowReservedIs409ListingTheNightAndTheWholeBatchRollsBack() throws Exception {
        String rt = createRoomType(ownerA);
        putOk(rt, ownerA, body(entry(day(3), 5), entry(day(4), 5)));
        setReserved(rt, day(4), 3);
        String before = snapshot(rt);

        mvc.perform(json(put(inventory(rt)), body(entry(day(3), 8), entry(day(4), 2), entry(day(5), 1)))
                        .with(admin(ownerA)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVENTORY_BELOW_RESERVED"))
                .andExpect(jsonPath("$.nights", contains(day(4).toString())));

        /* day(3) was valid and day(5) new: both were written by the statement, then rolled back with the 409. */
        assertThat(snapshot(rt)).isEqualTo(before);
        assertThat(rowCount(rt)).isEqualTo(2);
    }

    @Test
    void totalEqualToReservedIsAccepted() throws Exception {
        String rt = createRoomType(ownerA);
        putOk(rt, ownerA, body(entry(day(1), 5)));
        setReserved(rt, day(1), 3);

        putOk(rt, ownerA, body(entry(day(1), 3)));

        assertThat(row(rt, day(1))).containsEntry("total", 3).containsEntry("reserved", 3);
    }

    @Test
    void windowEdgesAreAcceptedAndTheLastNightLandsInAMonthlyPartition() throws Exception {
        String rt = createRoomType(ownerA);

        putOk(rt, ownerA, body(entry(day(0), 1), entry(day(179), 1)));

        String partition = jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM room_inventory WHERE room_type_id = ?::uuid AND night = ?",
                String.class,
                rt,
                day(179));
        assertThat(partition).startsWith("room_inventory_p").isNotEqualTo("room_inventory_default");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM room_inventory_default WHERE room_type_id = ?::uuid", Integer.class, rt))
                .isZero();
    }

    @Test
    void nightsOutsideTheWindowAre400ListingThemAndNothingIsWritten() throws Exception {
        String rt = createRoomType(ownerA);

        mvc.perform(json(put(inventory(rt)), body(entry(day(180), 1), entry(day(1), 1), entry(day(-1), 1)))
                        .with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NIGHT_OUTSIDE_WINDOW"))
                .andExpect(jsonPath("$.nights", contains(day(-1).toString(), day(180).toString())));

        assertThat(rowCount(rt)).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "[null]",
                "{}",
                "[{\"night\":\"D1\",\"total\":1},{\"night\":\"D1\",\"total\":2}]",
                "[{\"total\":1}]",
                "[{\"night\":null,\"total\":1}]",
                "[{\"night\":\"D1\"}]",
                "[{\"night\":\"D1\",\"total\":null}]",
                "[{\"night\":\"D1\",\"total\":-1}]",
                "[{\"night\":\"D1\",\"total\":1001}]",
                "[{\"night\":\"2026-02-30\",\"total\":1}]",
                "[{\"night\":\"not-a-date\",\"total\":1}]",
                "[{\"night\":\"D1\",\"total\":\"five\"}]",
                "[{\"night\":"
            })
    void invalidInputIs400ValidationFailedAndNothingIsWritten(String template) throws Exception {
        String rt = createRoomType(ownerA);

        mvc.perform(json(put(inventory(rt)), template.replace("D1", day(1).toString()))
                        .with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(rowCount(rt)).isZero();
    }

    @Test
    void moreThan180EntriesIs400AndExactly180IsAccepted() throws Exception {
        String rt = createRoomType(ownerA);
        String all180 = IntStream.range(0, 180).mapToObj(i -> entry(day(i), 1)).collect(Collectors.joining(","));

        mvc.perform(json(put(inventory(rt)), "[" + all180 + "," + entry(day(0), 2) + "]")
                        .with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(rowCount(rt)).isZero();

        putOk(rt, ownerA, "[" + all180 + "]");
        assertThat(rowCount(rt)).isEqualTo(180);
    }

    @Test
    void totalBoundsZeroAndThousandAreAccepted() throws Exception {
        String rt = createRoomType(ownerA);

        putOk(rt, ownerA, body(entry(day(1), 0), entry(day(2), 1000)));

        assertThat(row(rt, day(2))).containsEntry("total", 1000);
    }

    @Test
    void otherOwnerIs404RoomTypeNotFoundAndNothingChanges() throws Exception {
        String rt = createRoomType(ownerA);
        putOk(rt, ownerA, body(entry(day(1), 5)));
        String before = snapshot(rt);

        mvc.perform(json(put(inventory(rt)), body(entry(day(1), 9), entry(day(2), 9)))
                        .with(admin(ownerB)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROOM_TYPE_NOT_FOUND"));

        assertThat(snapshot(rt)).isEqualTo(before);
    }

    @Test
    void unknownRoomTypeIs404RoomTypeNotFound() throws Exception {
        String unknown = UUID.randomUUID().toString();

        mvc.perform(json(put(inventory(unknown)), body(entry(day(1), 1))).with(admin(ownerA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_TYPE_NOT_FOUND"));

        assertThat(rowCount(unknown)).isZero();
    }

    @Test
    void nonUuidRoomTypeIdIs400() throws Exception {
        mvc.perform(json(put(inventory("not-a-uuid")), body(entry(day(1), 1))).with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(json(put(inventory(UUID.randomUUID().toString())), body(entry(day(1), 1))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestIs403AndNothingIsWritten() throws Exception {
        String rt = createRoomType(ownerA);

        mvc.perform(json(put(inventory(rt)), body(entry(day(1), 1))).with(guest()))
                .andExpect(status().isForbidden());

        assertThat(rowCount(rt)).isZero();
    }

    /* B1: the URL rule rejects a GUEST before MVC parses or validates the body (403, never 400). */
    @ParameterizedTest
    @ValueSource(strings = {"[]", "[{\"night\":null,\"total\":-1}]", "[{\"night\":"})
    void guestWithInvalidBodyIs403Not400(String body) throws Exception {
        String rt = createRoomType(ownerA);

        mvc.perform(json(put(inventory(rt)), body).with(guest())).andExpect(status().isForbidden());

        assertThat(rowCount(rt)).isZero();
    }
}
