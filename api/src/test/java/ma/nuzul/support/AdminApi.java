package ma.nuzul.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Helpers shared by the admin API ITs: callers (JWT post-processors), a JSON request builder and catalog setup
 * through the real API. Table-specific assertions (row, snapshot, rowCount) stay in each IT.
 */
public final class AdminApi {

    public static final String HOTELS = "/api/v1/admin/hotels";
    public static final String PROBLEM_JSON = "application/problem+json";
    public static final String MINIMAL_HOTEL_JSON = "{\"name\":\"Riad\",\"city\":\"Fes\"}";
    public static final String MINIMAL_ROOM_TYPE_JSON = "{\"name\":\"Double\",\"capacity\":2}";

    private AdminApi() {}

    /**
     * A fresh owner sub per call (JUnit builds a new test instance per method): no test can see another test's or
     * another IT class's rows, so no cleanup is needed and nothing has to be deleted around FKs.
     */
    public static String ownerSub(String label) {
        return label + "-" + UUID.randomUUID();
    }

    public static JwtRequestPostProcessor admin(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("ROLE_HOTEL_ADMIN"));
    }

    /** The platform operator (search reindex); owns no hotels. */
    public static JwtRequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject("platform-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    public static JwtRequestPostProcessor guest() {
        return jwt().jwt(j -> j.subject("guest-1")).authorities(new SimpleGrantedAuthority("ROLE_GUEST"));
    }

    /** A GUEST token with the OIDC claims the hold reads (name, email), as Keycloak issues them. */
    public static JwtRequestPostProcessor guest(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("name", "Amina Guest").claim("email", sub + "@guest.test"))
                .authorities(new SimpleGrantedAuthority("ROLE_GUEST"));
    }

    public static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    public static String roomTypes(String hotelId) {
        return HOTELS + "/" + hotelId + "/room-types";
    }

    /** POSTs the hotel as {@code owner}, expects 201 and returns the new id. */
    public static String createHotel(MockMvc mvc, String owner, String body) throws Exception {
        return createdId(mvc, json(post(HOTELS), body).with(admin(owner)));
    }

    /** POSTs the room type under {@code hotelId} as {@code owner}, expects 201 and returns the new id. */
    public static String createRoomType(MockMvc mvc, String owner, String hotelId, String body) throws Exception {
        return createdId(mvc, json(post(roomTypes(hotelId)), body).with(admin(owner)));
    }

    /** A minimal hotel and one room type in it, both owned by {@code owner}; returns the room type id. */
    public static String createRoomType(MockMvc mvc, String owner) throws Exception {
        return createRoomType(mvc, owner, createHotel(mvc, owner, MINIMAL_HOTEL_JSON), MINIMAL_ROOM_TYPE_JSON);
    }

    private static String createdId(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
