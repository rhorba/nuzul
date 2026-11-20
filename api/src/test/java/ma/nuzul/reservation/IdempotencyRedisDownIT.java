package ma.nuzul.reservation;

import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Release gate 3, second half: every {@link IdempotencyIT} test with Redis unreachable (a closed local port). Each
 * cache call fails fast and is treated as a miss, so the Postgres unique constraint alone keeps the guarantee.
 */
@Import(IdempotencyRedisDownIT.UnreachableRedis.class)
class IdempotencyRedisDownIT extends IdempotencyIT {

    /** Wins over the Testcontainers service connection (a property would not: connection details take precedence). */
    @TestConfiguration(proxyBeanMethods = false)
    static class UnreachableRedis {
        @Bean
        @Primary
        DataRedisConnectionDetails unreachableRedis() {
            return new DataRedisConnectionDetails() {
                @Override
                public Standalone getStandalone() {
                    return Standalone.of("localhost", 1);
                }
            };
        }
    }

    @Override
    boolean redisUp() {
        return false;
    }
}
