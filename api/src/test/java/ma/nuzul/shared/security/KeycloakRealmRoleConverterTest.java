package ma.nuzul.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(b::claim);
        return b.build();
    }

    @Test
    void mapsRealmRolesToRolePrefixedAuthorities() {
        var auth = converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("GUEST", "HOTEL_ADMIN")))));

        assertThat(auth)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_GUEST", "ROLE_HOTEL_ADMIN");
    }

    @Test
    void noRealmAccessClaimGivesNoAuthorities() {
        assertThat(converter.convert(jwt(Map.of("scope", "openid")))).isEmpty();
    }

    @Test
    void realmAccessWithoutRolesGivesNoAuthorities() {
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of())))).isEmpty();
    }

    @Test
    void ignoresNonStringAndBlankRoles() {
        var auth = converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("GUEST", 42, " ")))));

        assertThat(auth).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_GUEST");
    }
}
