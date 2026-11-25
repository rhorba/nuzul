package ma.nuzul.inventory;

import static ma.nuzul.support.AdminApi.PROBLEM_JSON;
import static ma.nuzul.support.AdminApi.admin;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static ma.nuzul.support.AdminApi.ownerSub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.application.ManageRatesUseCase;
import ma.nuzul.inventory.domain.RateChange;
import ma.nuzul.support.AdminApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 1.3: owner-scoped bulk rate upsert, end to end through security, MVC and Postgres. Shares InventoryBulkIT's
 * fixed Clock (same imports, so the same cached Spring context).
 */
@Import({TestcontainersConfiguration.class, InventoryBulkIT.FixedClock.class})
@SpringBootTest
@AutoConfigureMockMvc
class RateBulkIT {

    private static final LocalDate TODAY = InventoryBulkIT.TODAY;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ManageRatesUseCase rates;

    /* Fresh owner subs per test: isolated from other tests and other IT classes, no FK-sensitive cleanup. */
    private final String ownerA = ownerSub("owner-a");
    private final String ownerB = ownerSub("owner-b");

    private static String rates(String roomTypeId) {
        return "/api/v1/admin/room-types/" + roomTypeId + "/rates";
    }

    private static LocalDate day(int plusDays) {
        return TODAY.plusDays(plusDays);
    }

    /** {@code price} is written into the JSON verbatim, so its exact decimal text reaches the server. */
    private static String entry(LocalDate night, String price, boolean refundable) {
        return "{\"night\":\"" + night + "\",\"priceMad\":" + price + ",\"refundable\":" + refundable + "}";
    }

    private static String body(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    private String createRoomType(String owner) throws Exception {
        return AdminApi.createRoomType(mvc, owner);
    }

    private void putOk(String roomTypeId, String owner, String body) throws Exception {
        mvc.perform(json(put(rates(roomTypeId)), body).with(admin(owner))).andExpect(status().isNoContent());
    }

    /** Every rate row of the room type, ordered by night, as one JSON text (null when there are none). */
    private String snapshot(String roomTypeId) {
        return jdbc.queryForObject(
                "SELECT json_agg(row_to_json(r) ORDER BY r.night)::text FROM rate r WHERE room_type_id = ?::uuid",
                String.class,
                roomTypeId);
    }

    private int rowCount(String roomTypeId) {
        return jdbc.queryForObject("SELECT count(*) FROM rate WHERE room_type_id = ?::uuid", Integer.class, roomTypeId);
    }

    /** price_mad as Postgres prints it (exact NUMERIC text), plus refundable and free_cancel_hours. */
    private Map<String, Object> row(String roomTypeId, LocalDate night) {
        return jdbc.queryForMap(
                "SELECT price_mad::text AS price, refundable, free_cancel_hours FROM rate"
                        + " WHERE room_type_id = ?::uuid AND night = ?",
                roomTypeId,
                night);
    }

    @Test
    void insertThenUpdateTheSameNights() throws Exception {
        String rt = createRoomType(ownerA);

        putOk(rt, ownerA, body(entry(day(2), "850.00", true), entry(day(1), "1234.5", false)));
        assertThat(row(rt, day(1)))
                .containsEntry("price", "1234.50")
                .containsEntry("refundable", false)
                .containsEntry("free_cancel_hours", 48);
        assertThat(row(rt, day(2))).containsEntry("price", "850.00").containsEntry("refundable", true);

        putOk(rt, ownerA, body(entry(day(1), "999.99", true), entry(day(2), "0.01", false)));

        assertThat(rowCount(rt)).isEqualTo(2);
        assertThat(row(rt, day(1))).containsEntry("price", "999.99").containsEntry("refundable", true);
        assertThat(row(rt, day(2))).containsEntry("price", "0.01").containsEntry("refundable", false);
    }

    @Test
    void priceRoundTripsExactlyIncludingTheMaximum() throws Exception {
        String rt = createRoomType(ownerA);

        putOk(
                rt,
                ownerA,
                body(
                        entry(day(1), "1234.5", true),
                        entry(day(2), "99999999.99", true),
                        // Scientific notation has a negative scale; it must be bound as plain text, not 1E+3.
                        entry(day(3), "1E+3", true),
                        entry(day(4), "8.5E+2", true)));

        assertThat(row(rt, day(1))).containsEntry("price", "1234.50");
        assertThat(row(rt, day(2))).containsEntry("price", "99999999.99");
        assertThat(row(rt, day(3))).containsEntry("price", "1000.00");
        assertThat(row(rt, day(4))).containsEntry("price", "850.00");
    }

    @Test
    void updateLeavesFreeCancelHoursUnchanged() throws Exception {
        String rt = createRoomType(ownerA);
        putOk(rt, ownerA, body(entry(day(1), "500", true)));
        jdbc.update("UPDATE rate SET free_cancel_hours = 72 WHERE room_type_id = ?::uuid AND night = ?", rt, day(1));

        putOk(rt, ownerA, body(entry(day(1), "600", false)));

        assertThat(row(rt, day(1)))
                .containsEntry("price", "600.00")
                .containsEntry("refundable", false)
                .containsEntry("free_cancel_hours", 72);
    }

    @Test
    void tooManyDecimalsIs400AndNothingIsWritten() throws Exception {
        String rt = createRoomType(ownerA);

        mvc.perform(json(put(rates(rt)), body(entry(day(1), "10.005", true))).with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(rowCount(rt)).isZero();
    }

    @Test
    void windowEdgesTodayAndTodayPlus179AreAccepted() throws Exception {
        String rt = createRoomType(ownerA);

        putOk(rt, ownerA, body(entry(day(0), "100", true), entry(day(179), "100", true)));

        assertThat(rowCount(rt)).isEqualTo(2);
    }

    @Test
    void nightsOutsideTheWindowAre400ListingThemAndNothingIsWritten() throws Exception {
        String rt = createRoomType(ownerA);

        mvc.perform(json(
                                put(rates(rt)),
                                body(
                                        entry(day(180), "100", true),
                                        entry(day(1), "100", true),
                                        entry(day(-1), "100", true)))
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
                "[{\"night\":\"D1\",\"priceMad\":1,\"refundable\":true},"
                        + "{\"night\":\"D1\",\"priceMad\":2,\"refundable\":true}]",
                "[{\"priceMad\":1,\"refundable\":true}]",
                "[{\"night\":null,\"priceMad\":1,\"refundable\":true}]",
                "[{\"night\":\"D1\",\"refundable\":true}]",
                "[{\"night\":\"D1\",\"priceMad\":null,\"refundable\":true}]",
                "[{\"night\":\"D1\",\"priceMad\":1}]",
                "[{\"night\":\"D1\",\"priceMad\":1,\"refundable\":null}]",
                "[{\"night\":\"D1\",\"priceMad\":0,\"refundable\":true}]",
                "[{\"night\":\"D1\",\"priceMad\":0.001,\"refundable\":true}]",
                "[{\"night\":\"D1\",\"priceMad\":-5,\"refundable\":true}]",
                "[{\"night\":\"D1\",\"priceMad\":10.005,\"refundable\":true}]",
                "[{\"night\":\"D1\",\"priceMad\":100000000,\"refundable\":true}]",
                "[{\"night\":\"D1\",\"priceMad\":1E+8,\"refundable\":true}]",
                // @Digits counts trailing zeros on a BigDecimal: the web layer is stricter than RateChange here.
                "[{\"night\":\"D1\",\"priceMad\":850.000,\"refundable\":true}]",
                "[{\"night\":\"D1\",\"priceMad\":\"cheap\",\"refundable\":true}]",
                "[{\"night\":\"D1\",\"priceMad\":1,\"refundable\":\"maybe\"}]",
                "[{\"night\":\"2026-02-30\",\"priceMad\":1,\"refundable\":true}]",
                "[{\"night\":\"not-a-date\",\"priceMad\":1,\"refundable\":true}]",
                "[{\"night\":"
            })
    void invalidInputIs400ValidationFailedAndNothingIsWritten(String template) throws Exception {
        String rt = createRoomType(ownerA);

        mvc.perform(json(put(rates(rt)), template.replace("D1", day(1).toString()))
                        .with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(rowCount(rt)).isZero();
    }

    @Test
    void moreThan180EntriesIs400AndExactly180IsAccepted() throws Exception {
        String rt = createRoomType(ownerA);
        String all180 = IntStream.range(0, 180)
                .mapToObj(i -> entry(day(i), "100", true))
                .collect(Collectors.joining(","));

        mvc.perform(json(put(rates(rt)), "[" + all180 + "," + entry(day(0), "200", true) + "]")
                        .with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(rowCount(rt)).isZero();

        putOk(rt, ownerA, "[" + all180 + "]");
        assertThat(rowCount(rt)).isEqualTo(180);
    }

    @Test
    void otherOwnerIs404RoomTypeNotFoundAndNothingChanges() throws Exception {
        String rt = createRoomType(ownerA);
        putOk(rt, ownerA, body(entry(day(1), "500", true)));
        String before = snapshot(rt);

        mvc.perform(json(put(rates(rt)), body(entry(day(1), "1", false), entry(day(2), "1", false)))
                        .with(admin(ownerB)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROOM_TYPE_NOT_FOUND"));

        assertThat(snapshot(rt)).isEqualTo(before);
    }

    @Test
    void unknownRoomTypeIs404RoomTypeNotFound() throws Exception {
        String unknown = UUID.randomUUID().toString();

        mvc.perform(json(put(rates(unknown)), body(entry(day(1), "100", true))).with(admin(ownerA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_TYPE_NOT_FOUND"));

        assertThat(rowCount(unknown)).isZero();
    }

    @Test
    void nonUuidRoomTypeIdIs400() throws Exception {
        mvc.perform(json(put(rates("not-a-uuid")), body(entry(day(1), "100", true)))
                        .with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(json(put(rates(UUID.randomUUID().toString())), body(entry(day(1), "100", true))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestIs403AndNothingIsWritten() throws Exception {
        String rt = createRoomType(ownerA);

        mvc.perform(json(put(rates(rt)), body(entry(day(1), "100", true))).with(guest()))
                .andExpect(status().isForbidden());

        assertThat(rowCount(rt)).isZero();
    }

    /* B1: the URL rule rejects a GUEST before MVC parses or validates the body (403, never 400). */
    @ParameterizedTest
    @ValueSource(strings = {"[]", "[{\"night\":null,\"priceMad\":10.005}]", "[{\"night\":"})
    void guestWithInvalidBodyIs403Not400(String body) throws Exception {
        String rt = createRoomType(ownerA);

        mvc.perform(json(put(rates(rt)), body).with(guest())).andExpect(status().isForbidden());

        assertThat(rowCount(rt)).isZero();
    }

    /** The room type's hotel, as catalog stored it. */
    private String hotelOf(String roomTypeId) {
        return jdbc.queryForObject("SELECT hotel_id::text FROM room_type WHERE id = ?::uuid", String.class, roomTypeId);
    }

    /** The HotelPricesChanged outbox rows of the hotel, oldest first. */
    private List<Map<String, Object>> priceEvents(String hotelId) {
        return jdbc.queryForList("""
                SELECT aggregate, aggregate_id::text AS aggregate_id, payload ->> 'hotelId' AS hotel_id,
                       payload ->> 'occurredAt' AS occurred_at
                FROM outbox_event
                WHERE type = 'HotelPricesChanged' AND aggregate_id = ?::uuid
                ORDER BY created_at, id
                """, hotelId);
    }

    /* Story 3.2: one event per call (not per night), keyed by the hotel so it is ordered with its catalog events. */
    @Test
    void eachCallWritesOneHotelPricesChangedEventKeyedByTheHotel() throws Exception {
        String rt = createRoomType(ownerA);
        String hotel = hotelOf(rt);

        putOk(rt, ownerA, body(entry(day(1), "500", true), entry(day(2), "600", true), entry(day(3), "700", true)));

        var rows = priceEvents(hotel);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("aggregate", "hotel")
                .containsEntry("aggregate_id", hotel)
                .containsEntry("hotel_id", hotel);
        assertThat(rows.get(0).get("occurred_at")).isNotNull();

        putOk(rt, ownerA, body(entry(day(1), "550", true)));
        assertThat(priceEvents(hotel)).hasSize(2);
    }

    @Test
    void refusedCallsWriteNoEvent() throws Exception {
        String rt = createRoomType(ownerA);
        String hotel = hotelOf(rt);

        mvc.perform(json(put(rates(rt)), body(entry(day(1), "1", false))).with(admin(ownerB)))
                .andExpect(status().isNotFound());
        mvc.perform(json(put(rates(rt)), body(entry(day(180), "1", false))).with(admin(ownerA)))
                .andExpect(status().isBadRequest());

        assertThat(priceEvents(hotel)).isEmpty();
    }

    /*
     * The event is appended before the upsert; a trigger (scoped to this test's room type) makes the upsert fail
     * after it, and the event must roll back with the rates: both are in setRates' one transaction.
     */
    @Test
    void aFailingUpsertRollsTheEventBack() throws Exception {
        String rt = createRoomType(ownerA);
        String hotel = hotelOf(rt);
        String suffix = rt.replace("-", "");
        jdbc.execute("CREATE FUNCTION fail_rate_" + suffix + "() RETURNS trigger LANGUAGE plpgsql AS"
                + " $$ BEGIN RAISE EXCEPTION 'upsert refused by test'; END $$");
        jdbc.execute("CREATE TRIGGER fail_rate_" + suffix + " BEFORE INSERT OR UPDATE ON rate FOR EACH ROW"
                + " WHEN (NEW.room_type_id = '" + rt + "'::uuid) EXECUTE FUNCTION fail_rate_" + suffix + "()");
        try {
            assertThatThrownBy(() -> rates.setRates(
                            ownerA, UUID.fromString(rt), List.of(new RateChange(day(1), new BigDecimal("500"), true))))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER fail_rate_" + suffix + " ON rate");
            jdbc.execute("DROP FUNCTION fail_rate_" + suffix + "()");
        }

        assertThat(priceEvents(hotel)).isEmpty();
        assertThat(rowCount(rt)).isZero();
    }
}
