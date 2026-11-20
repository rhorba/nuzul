package ma.nuzul.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Story 1.2: owner-scoped hotel admin CRUD, end to end through security, MVC, JPA and Postgres. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HotelAdminIT {

    private static final String BASE = "/api/v1/admin/hotels";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String MALFORMED = "Malformed request";

    private static final String RIAD_JSON = """
            {"name":"Riad Atlas","city":"Marrakech","address":"Derb 1, Medina","stars":4,
             "amenities":["wifi","pool"],"lat":31.629472,"lon":-7.981084}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /*
     * Fresh owner subs per test (JUnit builds a new instance per test method): no test can see another test's
     * or another IT class's rows, so no cleanup is needed and nothing has to be deleted around room_type FKs.
     */
    private final String ownerA = "owner-a-" + UUID.randomUUID();
    private final String ownerB = "owner-b-" + UUID.randomUUID();

    private static JwtRequestPostProcessor admin(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("ROLE_HOTEL_ADMIN"));
    }

    private static JwtRequestPostProcessor guest() {
        return jwt().jwt(j -> j.subject("guest-1")).authorities(new SimpleGrantedAuthority("ROLE_GUEST"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String createHotel(String owner, String name) throws Exception {
        String body = mvc.perform(
                        json(post(BASE), RIAD_JSON.replace("Riad Atlas", name)).with(admin(owner)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("SELECT * FROM hotel WHERE id = ?::uuid", id);
    }

    /** The whole row as JSON text, so two reads compare by value (java.sql.Array has identity equality). */
    private String snapshot(String id) {
        return jdbc.queryForObject("SELECT row_to_json(h)::text FROM hotel h WHERE id = ?::uuid", String.class, id);
    }

    @Test
    void createReturns201WithLocationAndBodyAndStoresCallerAsOwner() throws Exception {
        String body = mvc.perform(json(post(BASE), RIAD_JSON).with(admin(ownerA)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*" + BASE + "/[0-9a-f-]{36}$")))
                .andExpect(jsonPath("$.name").value("Riad Atlas"))
                .andExpect(jsonPath("$.city").value("Marrakech"))
                .andExpect(jsonPath("$.address").value("Derb 1, Medina"))
                .andExpect(jsonPath("$.stars").value(4))
                .andExpect(jsonPath("$.amenities", contains("wifi", "pool")))
                .andExpect(jsonPath("$.lat").value(31.629472))
                .andExpect(jsonPath("$.lon").value(-7.981084))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.ownerSub").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = JsonPath.read(body, "$.id");
        assertThat(row(id)).containsEntry("owner_sub", ownerA).containsEntry("status", "ACTIVE");
    }

    @Test
    void createIgnoresOwnerSubInTheBody() throws Exception {
        String hostile = RIAD_JSON.replace(
                "\"name\"", "\"ownerSub\":\"" + ownerB + "\",\"owner_sub\":\"" + ownerB + "\",\"name\"");

        String body = mvc.perform(json(post(BASE), hostile).with(admin(ownerA)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(row(JsonPath.read(body, "$.id"))).containsEntry("owner_sub", ownerA);
    }

    @Test
    void createWithOnlyRequiredFieldsUsesDefaults() throws Exception {
        mvc.perform(json(post(BASE), "{\"name\":\"Dar Fes\",\"city\":\"Fes\"}").with(admin(ownerA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amenities").isEmpty())
                .andExpect(jsonPath("$.stars").doesNotExist())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void ownerCanReadOwnHotel() throws Exception {
        String id = createHotel(ownerA, "Riad A");

        mvc.perform(get(BASE + "/" + id).with(admin(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Riad A"));
    }

    @Test
    void ownerCanUpdateAndRetireOwnHotel() throws Exception {
        String id = createHotel(ownerA, "Riad A");
        String update = """
                {"name":"Riad A Renamed","city":"Essaouira","address":null,"stars":5,
                 "amenities":["spa"],"lat":31.5,"lon":-9.77,"status":"INACTIVE"}
                """;

        mvc.perform(json(put(BASE + "/" + id), update).with(admin(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Riad A Renamed"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.version").value(1));

        Map<String, Object> row = row(id);
        assertThat(row)
                .containsEntry("name", "Riad A Renamed")
                .containsEntry("city", "Essaouira")
                .containsEntry("status", "INACTIVE")
                .containsEntry("owner_sub", ownerA);
        assertThat(row.get("address")).isNull();
        assertThat((BigDecimal) row.get("lon")).isEqualByComparingTo("-9.77");
        mvc.perform(get(BASE + "/" + id).with(admin(ownerA))).andExpect(jsonPath("$.amenities", contains("spa")));
    }

    @Test
    void updateWithoutStatusIs400() throws Exception {
        String id = createHotel(ownerA, "Riad A");

        mvc.perform(json(put(BASE + "/" + id), RIAD_JSON).with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void otherOwnerGetsNotFoundOnGetAndPutAndHotelIsUnchanged() throws Exception {
        String id = createHotel(ownerA, "Riad A");
        String before = snapshot(id);
        String hijack = RIAD_JSON.replace("Riad Atlas", "Hijacked").replace("}", ",\"status\":\"INACTIVE\"}");

        mvc.perform(get(BASE + "/" + id).with(admin(ownerB)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));
        mvc.perform(json(put(BASE + "/" + id), hijack).with(admin(ownerB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));

        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test
    void unknownHotelIs404ProblemWithCode() throws Exception {
        mvc.perform(get(BASE + "/" + UUID.randomUUID()).with(admin(ownerA)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("HOTEL_NOT_FOUND"));
    }

    @Test
    void listReturnsOnlyCallersHotelsNewestFirstWithPageMetadata() throws Exception {
        createHotel(ownerA, "A1");
        createHotel(ownerB, "B1");
        createHotel(ownerA, "A2");
        createHotel(ownerA, "A3");

        mvc.perform(get(BASE).param("page", "0").param("size", "20").with(admin(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name", contains("A3", "A2", "A1")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void listDefaultsToFirstPageOfTwentyAndPaginates() throws Exception {
        createHotel(ownerA, "A1");
        createHotel(ownerA, "A2");

        mvc.perform(get(BASE).with(admin(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get(BASE).param("page", "1").param("size", "1").with(admin(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name", contains("A1")))
                .andExpect(jsonPath("$.total").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=101", "size=0", "page=-1"})
    void outOfRangePagingIsRejectedWith400(String param) throws Exception {
        String[] kv = param.split("=");

        mvc.perform(get(BASE).param(kv[0], kv[1]).with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(json(post(BASE), RIAD_JSON)).andExpect(status().isUnauthorized());
    }

    @Test
    void guestRoleIs403OnEveryEndpoint() throws Exception {
        String id = createHotel(ownerA, "Riad A");
        String update = RIAD_JSON.replace("}", ",\"status\":\"ACTIVE\"}");

        mvc.perform(get(BASE).with(guest())).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + id).with(guest())).andExpect(status().isForbidden());
        mvc.perform(json(post(BASE), RIAD_JSON).with(guest())).andExpect(status().isForbidden());
        mvc.perform(json(put(BASE + "/" + id), update).with(guest())).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"name\":\"\",\"city\":\"Fes\"}",
                "{\"name\":\"Dar\",\"city\":\"  \"}",
                "{\"city\":\"Fes\"}",
                "{\"name\":\"Dar\",\"city\":\"Fes\",\"stars\":0}",
                "{\"name\":\"Dar\",\"city\":\"Fes\",\"stars\":6}",
                "{\"name\":\"Dar\",\"city\":\"Fes\",\"lat\":-90.5}",
                "{\"name\":\"Dar\",\"city\":\"Fes\",\"lat\":91}",
                "{\"name\":\"Dar\",\"city\":\"Fes\",\"lon\":-181}",
                "{\"name\":\"Dar\",\"city\":\"Fes\",\"lon\":180.1}"
            })
    void invalidInputIs400ProblemWithValidationFailedCode(String body) throws Exception {
        mvc.perform(json(post(BASE), body).with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel WHERE owner_sub = ?", Integer.class, ownerA))
                .isZero();
    }

    @Test
    void locationHeaderPointsAtTheCreatedHotel() throws Exception {
        String location = mvc.perform(json(post(BASE), RIAD_JSON).with(admin(ownerA)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        assertThat(location).isNotNull();
        mvc.perform(get(location).with(admin(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", endsWith(location.substring(location.lastIndexOf('/') + 1))));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"name\":\"Dar\",\"city\":\"Fes\",\"amenities\":[null]}",
                "{\"name\":\"Dar\",\"city\":\"Fes\",\"amenities\":[\"wifi\",\" \"]}",
                "{\"name\":\"Dar\",\"city\":\"Fes\",\"lat\":31.1234567}",
                "{\"name\":\"Dar\",\"city\":\"Fes\",\"lon\":-7.1234567}"
            })
    void nullOrBlankAmenityAndOverPreciseCoordinatesAre400(String body) throws Exception {
        mvc.perform(json(post(BASE), body).with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void nullAmenityOnUpdateIs400NotServerError() throws Exception {
        String id = createHotel(ownerA, "Riad A");
        String update = "{\"name\":\"Dar\",\"city\":\"Fes\",\"amenities\":[null],\"status\":\"ACTIVE\"}";

        mvc.perform(json(put(BASE + "/" + id), update).with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void oversizeFieldsAre400() throws Exception {
        String longName = "x".repeat(201);
        String fiftyOneAmenities = String.join(",", Collections.nCopies(51, "\"wifi\""));
        String longAmenity = "a".repeat(51);

        for (String body : new String[] {
            "{\"name\":\"" + longName + "\",\"city\":\"Fes\"}",
            "{\"name\":\"Dar\",\"city\":\"" + longName + "\"}",
            "{\"name\":\"Dar\",\"city\":\"Fes\",\"address\":\"" + "x".repeat(501) + "\"}",
            "{\"name\":\"Dar\",\"city\":\"Fes\",\"amenities\":[" + fiftyOneAmenities + "]}",
            "{\"name\":\"Dar\",\"city\":\"Fes\",\"amenities\":[\"" + longAmenity + "\"]}"
        }) {
            mvc.perform(json(post(BASE), body).with(admin(ownerA)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hotel WHERE owner_sub = ?", Integer.class, ownerA))
                .isZero();
    }

    @Test
    void malformedJsonIs400WithGenericDetail() throws Exception {
        mvc.perform(json(post(BASE), "{\"name\":").with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(MALFORMED));
    }

    @Test
    void unknownEnumValueIs400WithoutLeakingInternals() throws Exception {
        String id = createHotel(ownerA, "Riad A");
        String update = RIAD_JSON.replace("}", ",\"status\":\"DELETED\"}");

        String body = mvc.perform(json(put(BASE + "/" + id), update).with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(MALFORMED))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("ma.nuzul", "HotelStatus", "java.", "Exception");
    }

    @Test
    void nonUuidPathAndNonNumericPageAre400() throws Exception {
        String body = mvc.perform(get(BASE + "/not-a-uuid").with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(MALFORMED))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("java.", "UUID", "Exception");

        mvc.perform(get(BASE).param("page", "abc").with(admin(ownerA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(MALFORMED));
    }

    @Test
    void listBreaksCreatedAtTiesByIdDescending() throws Exception {
        // Canonical lowercase UUID text sorts like Postgres' uuid ordering (unsigned bytes).
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String high = a.compareTo(b) > 0 ? a : b;
        String low = high.equals(a) ? b : a;
        for (String[] row : new String[][] {{low, "Low"}, {high, "High"}}) {
            jdbc.update(
                    "INSERT INTO hotel (id, owner_sub, name, city, created_at)"
                            + " VALUES (?::uuid, ?, ?, 'Fes', TIMESTAMPTZ '2026-01-01 00:00:00+00')",
                    row[0],
                    ownerA,
                    row[1]);
        }

        mvc.perform(get(BASE).with(admin(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name", contains("High", "Low")));
    }

    /* B1: the URL rule rejects a GUEST before MVC parses or validates the body (403, never 400). */
    @Test
    void guestWithInvalidBodyIs403Not400() throws Exception {
        String id = createHotel(ownerA, "Riad A");
        String before = snapshot(id);

        mvc.perform(json(post(BASE), "{\"name\":\"\"}").with(guest())).andExpect(status().isForbidden());
        mvc.perform(json(post(BASE), "{\"name\":").with(guest())).andExpect(status().isForbidden());
        mvc.perform(json(put(BASE + "/" + id), "{\"stars\":9}").with(guest())).andExpect(status().isForbidden());

        assertThat(snapshot(id)).isEqualTo(before);
    }
}
