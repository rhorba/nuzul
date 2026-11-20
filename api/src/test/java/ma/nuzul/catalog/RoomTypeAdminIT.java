package ma.nuzul.catalog;

import static ma.nuzul.support.AdminApi.PROBLEM_JSON;
import static ma.nuzul.support.AdminApi.admin;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static ma.nuzul.support.AdminApi.ownerSub;
import static ma.nuzul.support.AdminApi.roomTypes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.support.AdminApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Story 1.2: owner-scoped room-type admin CRUD under a hotel, end to end through security, MVC, JPA, Postgres. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomTypeAdminIT {

    private static final String DOUBLE_JSON = "{\"name\":\"Double\",\"capacity\":2,\"bedConfig\":\"1 queen\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /* Fresh owner subs per test: isolated from other tests and other IT classes, no FK-sensitive cleanup. */
    private final String ownerA = ownerSub("owner-a");
    private final String ownerB = ownerSub("owner-b");

    private String createHotel(String owner) throws Exception {
        return AdminApi.createHotel(mvc, owner, AdminApi.MINIMAL_HOTEL_JSON);
    }

    private String createRoomType(String owner, String hotelId, String body) throws Exception {
        return AdminApi.createRoomType(mvc, owner, hotelId, body);
    }

    private int roomTypeCount(String hotelId) {
        return jdbc.queryForObject("SELECT count(*) FROM room_type WHERE hotel_id = ?::uuid", Integer.class, hotelId);
    }

    private String snapshot(String roomTypeId) {
        return jdbc.queryForObject(
                "SELECT row_to_json(r)::text FROM room_type r WHERE id = ?::uuid", String.class, roomTypeId);
    }

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        String h = createHotel(ownerA);

        String body = mvc.perform(json(post(roomTypes(h)), DOUBLE_JSON).with(admin(ownerA)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*" + roomTypes(h) + "/[0-9a-f-]{36}$")))
                .andExpect(jsonPath("$.hotelId").value(h))
                .andExpect(jsonPath("$.name").value("Double"))
                .andExpect(jsonPath("$.capacity").value(2))
                .andExpect(jsonPath("$.bedConfig").value("1 queen"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = JsonPath.read(body, "$.id");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM room_type WHERE id = ?::uuid", id);
        assertThat(row.get("hotel_id")).hasToString(h);
        assertThat(row).containsEntry("name", "Double").containsEntry("bed_config", "1 queen");
        assertThat(((Number) row.get("capacity")).intValue()).isEqualTo(2);
    }

    @Test
    void createWithoutBedConfigStoresNull() throws Exception {
        String h = createHotel(ownerA);

        mvc.perform(json(post(roomTypes(h)), "{\"name\":\"Single\",\"capacity\":1}")
                        .with(admin(ownerA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bedConfig").doesNotExist());
    }

    @Test
    void listReturnsOnlyThatHotelsRoomTypesOrderedByName() throws Exception {
        String h1 = createHotel(ownerA);
        String h2 = createHotel(ownerA);
        createRoomType(ownerA, h1, "{\"name\":\"Suite\",\"capacity\":4}");
        createRoomType(ownerA, h1, "{\"name\":\"Double\",\"capacity\":2}");
        createRoomType(ownerA, h1, "{\"name\":\"Family\",\"capacity\":5}");
        createRoomType(ownerA, h2, "{\"name\":\"Other\",\"capacity\":2}");

        mvc.perform(get(roomTypes(h1)).with(admin(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].name", contains("Double", "Family", "Suite")))
                .andExpect(jsonPath("$[*].hotelId", contains(h1, h1, h1)));
    }

    @Test
    void listOfHotelWithoutRoomTypesIsEmptyArray() throws Exception {
        String h = createHotel(ownerA);

        mvc.perform(get(roomTypes(h)).with(admin(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void updateReturns200AndPersists() throws Exception {
        String h = createHotel(ownerA);
        String id = createRoomType(ownerA, h, DOUBLE_JSON);

        mvc.perform(json(put(roomTypes(h) + "/" + id), "{\"name\":\"Twin\",\"capacity\":3}")
                        .with(admin(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.hotelId").value(h))
                .andExpect(jsonPath("$.name").value("Twin"))
                .andExpect(jsonPath("$.capacity").value(3))
                .andExpect(jsonPath("$.bedConfig").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM room_type WHERE id = ?::uuid", id);
        assertThat(row).containsEntry("name", "Twin");
        assertThat(row.get("bed_config")).isNull();
        assertThat(((Number) row.get("capacity")).intValue()).isEqualTo(3);
        assertThat(row.get("hotel_id")).hasToString(h);
    }

    @Test
    void otherOwnerGetsHotelNotFoundOnEveryEndpointAndNothingChanges() throws Exception {
        String h = createHotel(ownerA);
        String id = createRoomType(ownerA, h, DOUBLE_JSON);
        String before = snapshot(id);

        mvc.perform(json(post(roomTypes(h)), "{\"name\":\"Planted\",\"capacity\":2}")
                        .with(admin(ownerB)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));
        mvc.perform(get(roomTypes(h)).with(admin(ownerB)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));
        mvc.perform(json(put(roomTypes(h) + "/" + id), "{\"name\":\"Hijacked\",\"capacity\":9}")
                        .with(admin(ownerB)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));

        assertThat(roomTypeCount(h)).isEqualTo(1);
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test
    void updateUnderTheWrongHotelIsRoomTypeNotFoundAndLeavesItUnchanged() throws Exception {
        String h1 = createHotel(ownerA);
        String h2 = createHotel(ownerA);
        String r = createRoomType(ownerA, h1, DOUBLE_JSON);
        String before = snapshot(r);

        mvc.perform(json(put(roomTypes(h2) + "/" + r), "{\"name\":\"Moved\",\"capacity\":2}")
                        .with(admin(ownerA)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROOM_TYPE_NOT_FOUND"));

        assertThat(snapshot(r)).isEqualTo(before);
        assertThat(roomTypeCount(h2)).isZero();
    }

    @Test
    void unknownRoomTypeIsRoomTypeNotFound() throws Exception {
        String h = createHotel(ownerA);

        mvc.perform(json(put(roomTypes(h) + "/" + UUID.randomUUID()), DOUBLE_JSON)
                        .with(admin(ownerA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOM_TYPE_NOT_FOUND"));
        assertThat(roomTypeCount(h)).isZero();
    }

    @Test
    void unknownHotelIsHotelNotFound() throws Exception {
        String unknown = UUID.randomUUID().toString();

        mvc.perform(json(post(roomTypes(unknown)), DOUBLE_JSON).with(admin(ownerA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));
        mvc.perform(get(roomTypes(unknown)).with(admin(ownerA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));
        mvc.perform(json(put(roomTypes(unknown) + "/" + UUID.randomUUID()), DOUBLE_JSON)
                        .with(admin(ownerA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"name\":\"\",\"capacity\":2}",
                "{\"name\":\"   \",\"capacity\":2}",
                "{\"capacity\":2}",
                "{\"name\":\"Double\"}",
                "{\"name\":\"Double\",\"capacity\":0}",
                "{\"name\":\"Double\",\"capacity\":-1}",
                "{\"name\":\"Double\",\"capacity\":21}",
                "{\"name\":\"NAME201\",\"capacity\":2}",
                "{\"name\":\"Double\",\"capacity\":2,\"bedConfig\":\"BED201\"}",
                "{\"name\":\"Double\",\"capacity\":\"two\"}",
                "{\"name\":"
            })
    void invalidInputIs400ValidationFailedOnCreateAndUpdate(String template) throws Exception {
        String body = template.replace("NAME201", "n".repeat(201)).replace("BED201", "b".repeat(201));
        String h = createHotel(ownerA);
        String id = createRoomType(ownerA, h, DOUBLE_JSON);
        String before = snapshot(id);

        mvc.perform(json(post(roomTypes(h)), body).with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(json(put(roomTypes(h) + "/" + id), body).with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(roomTypeCount(h)).isEqualTo(1);
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test
    void boundaryCapacityAndLengthsAreAccepted() throws Exception {
        String h = createHotel(ownerA);
        String body =
                "{\"name\":\"" + "n".repeat(200) + "\",\"capacity\":20,\"bedConfig\":\"" + "b".repeat(200) + "\"}";

        mvc.perform(json(post(roomTypes(h)), body).with(admin(ownerA))).andExpect(status().isCreated());
    }

    @Test
    void nonUuidRoomTypeIdIs400() throws Exception {
        String h = createHotel(ownerA);

        mvc.perform(json(put(roomTypes(h) + "/not-a-uuid"), DOUBLE_JSON).with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void noTokenIs401() throws Exception {
        String h = UUID.randomUUID().toString();

        mvc.perform(get(roomTypes(h))).andExpect(status().isUnauthorized());
        mvc.perform(json(post(roomTypes(h)), DOUBLE_JSON)).andExpect(status().isUnauthorized());
        mvc.perform(json(put(roomTypes(h) + "/" + UUID.randomUUID()), DOUBLE_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestRoleIs403OnEveryEndpoint() throws Exception {
        String h = createHotel(ownerA);
        String id = createRoomType(ownerA, h, DOUBLE_JSON);

        mvc.perform(get(roomTypes(h)).with(guest())).andExpect(status().isForbidden());
        mvc.perform(json(post(roomTypes(h)), DOUBLE_JSON).with(guest())).andExpect(status().isForbidden());
        mvc.perform(json(put(roomTypes(h) + "/" + id), DOUBLE_JSON).with(guest()))
                .andExpect(status().isForbidden());
        assertThat(roomTypeCount(h)).isEqualTo(1);
    }

    /* B1: the URL rule rejects a GUEST before MVC parses or validates the body (403, never 400). */
    @Test
    void guestWithInvalidBodyIs403Not400() throws Exception {
        String h = createHotel(ownerA);
        String id = createRoomType(ownerA, h, DOUBLE_JSON);
        String before = snapshot(id);

        mvc.perform(json(post(roomTypes(h)), "{\"capacity\":0}").with(guest())).andExpect(status().isForbidden());
        mvc.perform(json(put(roomTypes(h) + "/" + id), "{\"name\":").with(guest()))
                .andExpect(status().isForbidden());

        assertThat(roomTypeCount(h)).isEqualTo(1);
        assertThat(snapshot(id)).isEqualTo(before);
    }
}
