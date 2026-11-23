package ma.nuzul.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.ssl.SslBundle;

/**
 * The limiter's RedisURI. TLS must follow a {@code rediss://} URL too: Boot only exposes an SSL bundle when
 * {@code spring.data.redis.ssl.enabled} is set, and plaintext to a TLS Redis would make the limiter fail open forever.
 */
class RateLimitConfigTest {

    static final Duration TIMEOUT = Duration.ofMillis(300);

    static DataRedisConnectionDetails details(@Nullable SslBundle ssl, @Nullable String user, @Nullable String pass) {
        return new DataRedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of("redis.example", 6380, 2);
            }

            @Override
            public SslBundle getSslBundle() {
                return ssl;
            }

            @Override
            public String getUsername() {
                return user;
            }

            @Override
            public String getPassword() {
                return pass;
            }
        };
    }

    @Test
    void plainRedisUrlHasNoTls() {
        var uri = RateLimitConfig.redisUri(details(null, null, null), "redis://redis.example:6380/2", TIMEOUT);

        assertThat(uri.isSsl()).isFalse();
        assertThat(uri.getHost()).isEqualTo("redis.example");
        assertThat(uri.getPort()).isEqualTo(6380);
        assertThat(uri.getDatabase()).isEqualTo(2);
        assertThat(uri.getTimeout()).isEqualTo(TIMEOUT);
    }

    @Test
    void redissUrlUsesTls() {
        var uri = RateLimitConfig.redisUri(details(null, null, null), "rediss://redis.example:6380/2", TIMEOUT);

        assertThat(uri.isSsl()).isTrue();
    }

    @Test
    void sslBundleUsesTls() {
        var uri = RateLimitConfig.redisUri(details(mock(SslBundle.class), null, null), null, TIMEOUT);

        assertThat(uri.isSsl()).isTrue();
    }

    @Test
    void noUrlAndNoBundleHasNoTls() {
        var uri = RateLimitConfig.redisUri(details(null, null, null), null, TIMEOUT);

        assertThat(uri.isSsl()).isFalse();
    }

    @Test
    void credentialsArePassedOn() {
        var withUser = RateLimitConfig.redisUri(details(null, "app", "secret"), null, TIMEOUT);
        var passwordOnly = RateLimitConfig.redisUri(details(null, null, "secret"), null, TIMEOUT);

        var user = withUser.getCredentialsProvider().resolveCredentials().block();
        var pass = passwordOnly.getCredentialsProvider().resolveCredentials().block();

        assertThat(user).isNotNull();
        assertThat(user.getUsername()).isEqualTo("app");
        assertThat(user.getPassword()).isEqualTo("secret".toCharArray());
        assertThat(pass).isNotNull();
        assertThat(pass.getUsername()).isNull();
        assertThat(pass.getPassword()).isEqualTo("secret".toCharArray());
    }
}
