package ma.nuzul.reservation.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import ma.nuzul.reservation.application.HeldReservation;
import ma.nuzul.reservation.application.IdempotencyCache.Entry;
import ma.nuzul.reservation.domain.HoldRequest;
import ma.nuzul.reservation.domain.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** The cache's wire format and its fail-open behaviour; the IT runs it against a real Redis. */
class RedisIdempotencyCacheTest {

    private static final Entry ENTRY = new Entry(
            new HoldRequest(UUID.randomUUID(), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 4), 2),
            new HeldReservation(
                    UUID.randomUUID(),
                    ReservationStatus.HELD,
                    Instant.parse("2026-11-20T10:25:30.123456Z"),
                    new BigDecimal("3903.00")));

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisIdempotencyCache cache = new RedisIdempotencyCache(redis);

    {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void encodeDecodeRoundTripsExactly() {
        assertThat(RedisIdempotencyCache.decode(RedisIdempotencyCache.encode(ENTRY)))
                .contains(ENTRY);
        assertThat(RedisIdempotencyCache.encode(ENTRY)).contains("|3903.00|").startsWith("v1|");
    }

    @Test
    void unreadableValuesAreMisses() {
        String good = RedisIdempotencyCache.encode(ENTRY);

        assertThat(RedisIdempotencyCache.decode("v2" + good.substring(2))).isEmpty();
        assertThat(RedisIdempotencyCache.decode(good + "|extra")).isEmpty();
        assertThat(RedisIdempotencyCache.decode(good.replace("HELD", "NOPE"))).isEmpty();
        assertThat(RedisIdempotencyCache.decode("")).isEmpty();
    }

    @Test
    void writesWithTheKeyAndA24HourTtl() {
        UUID key = UUID.randomUUID();
        cache.put("guest-1", key, ENTRY);

        org.mockito.Mockito.verify(ops)
                .set("idem:guest-1:" + key, RedisIdempotencyCache.encode(ENTRY), Duration.ofHours(24));
    }

    @Test
    void readsAStoredEntry() {
        UUID key = UUID.randomUUID();
        when(ops.get("idem:guest-1:" + key)).thenReturn(RedisIdempotencyCache.encode(ENTRY));

        assertThat(cache.get("guest-1", key)).contains(ENTRY);
        assertThat(cache.get("guest-2", key)).isEmpty();
    }

    @Test
    void aRedisOutageIsAMissAndADroppedWrite() {
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        doThrow(new RedisConnectionFailureException("down"))
                .when(ops)
                .set(anyString(), anyString(), any(Duration.class));

        assertThat(cache.get("guest-1", UUID.randomUUID())).isEmpty();
        assertThatNoException().isThrownBy(() -> cache.put("guest-1", UUID.randomUUID(), ENTRY));
    }
}
