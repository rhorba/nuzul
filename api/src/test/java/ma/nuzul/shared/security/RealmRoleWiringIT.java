package ma.nuzul.shared.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves role mapping through the real resource-server chain: a bearer token goes through
 * BearerTokenAuthenticationFilter → JwtAuthenticationProvider → the converter SecurityConfig wires in. Only the
 * signature check is stubbed (JwtDecoder); the decoded JWT carries nothing but Keycloak's {@code realm_access.roles}.
 * If the converter were dropped from the chain, the HOTEL_ADMIN case would get 403 and this test would fail.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RealmRoleWiringIT {

    private static final String HOTELS = "/api/v1/admin/hotels";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private void tokenWithRealmRoles(String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("wiring-" + UUID.randomUUID())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(jwt);
    }

    @Test
    void hotelAdminRealmRoleOpensTheAdminApi() throws Exception {
        tokenWithRealmRoles("HOTEL_ADMIN");
        mvc.perform(get(HOTELS).header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andExpect(status().isOk());
    }

    @Test
    void guestRealmRoleIsForbidden() throws Exception {
        tokenWithRealmRoles("GUEST");
        mvc.perform(get(HOTELS).header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andExpect(status().isForbidden());
    }
}
