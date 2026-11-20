package ma.nuzul.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Stateless JWT resource server; Keycloak issues the tokens (ADR-5). Realm roles become ROLE_* authorities. */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    /**
     * CSRF is off on purpose (java:S4502 reviewed): the API is stateless (no HTTP session), authenticates only via
     * the {@code Authorization: Bearer} header and sets no auth cookies, so a cross-site request carries no
     * credentials the browser could attach. Revisit if cookie-based auth is ever introduced.
     */
    @Bean
    @SuppressWarnings("java:S4502")
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**")
                        .permitAll()
                        /*
                         * First authorization layer: decided in the filter chain, before Spring MVC parses and
                         * validates the @RequestBody, so a GUEST sending an invalid body gets 403, not 400. The
                         * controllers' @PreAuthorize("hasRole('HOTEL_ADMIN')") stays as the second layer. The two
                         * MUST agree. Scoped to the hotel-admin resources only, so a platform endpoint such as
                         * /api/v1/admin/search/reindex (PLATFORM_ADMIN, database doc §5) gets its own rule.
                         */
                        .requestMatchers("/api/v1/admin/hotels/**", "/api/v1/admin/room-types/**")
                        .hasRole("HOTEL_ADMIN")
                        .requestMatchers("/api/v1/reservations/**")
                        .hasRole("GUEST")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    /** A bean (not a private factory) so tests can run a realm_access-only JWT through the exact converter in use. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }
}
