package ma.nuzul.shared.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps Keycloak realm roles ({@code realm_access.roles}) to {@code ROLE_*} authorities (ADR-5). A role only
 * opens the door; data scope (e.g. {@code hotel.owner_sub = jwt.sub}) is still enforced in SQL.
 */
class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!(jwt.getClaims().get("realm_access") instanceof Map<?, ?> realmAccess)
                || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(r -> !r.isBlank())
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }
}
