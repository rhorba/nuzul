package ma.nuzul.reservation.adapter.out.redis;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.reservation.application.HeldReservation;
import ma.nuzul.reservation.application.IdempotencyCache;
import ma.nuzul.reservation.domain.HoldRequest;
import ma.nuzul.reservation.domain.ReservationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code idem:{guestSub}:{key}} → one versioned, pipe-separated line, expiring after 24 h (system design,
 * Idempotency). A flat line rather than JSON: nine fixed fields, none of which can contain a pipe (UUIDs, ISO dates
 * and instants, plain decimals, an enum name). Any Redis failure is logged and treated as a miss.
 */
@Component
class RedisIdempotencyCache implements IdempotencyCache {

    static final Duration TTL = Duration.ofHours(24);
    static final String VERSION = "v1";

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyCache.class);

    private final StringRedisTemplate redis;

    RedisIdempotencyCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(String guestSub, UUID idempotencyKey) {
        return "idem:" + guestSub + ":" + idempotencyKey;
    }

    @Override
    public Optional<Entry> get(String guestSub, UUID idempotencyKey) {
        try {
            String value = redis.opsForValue().get(key(guestSub, idempotencyKey));
            return value == null ? Optional.empty() : decode(value);
        } catch (RuntimeException e) {
            log.warn("Idempotency cache read failed, falling back to Postgres: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(String guestSub, UUID idempotencyKey, Entry entry) {
        try {
            redis.opsForValue().set(key(guestSub, idempotencyKey), encode(entry), TTL);
        } catch (RuntimeException e) {
            log.warn("Idempotency cache write failed, Postgres stays authoritative: {}", e.toString());
        }
    }

    static String encode(Entry e) {
        HoldRequest q = e.request();
        HeldReservation r = e.response();
        return String.join(
                "|",
                VERSION,
                r.id().toString(),
                r.status().name(),
                r.holdExpiresAt().toString(),
                r.totalMad().toPlainString(),
                q.roomTypeId().toString(),
                q.checkIn().toString(),
                q.checkOut().toString(),
                Integer.toString(q.rooms()));
    }

    /** Anything unreadable (another version, a corrupt value) is a miss: Postgres answers instead. */
    static Optional<Entry> decode(String value) {
        String[] f = value.split("\\|", -1);
        if (f.length != 9 || !VERSION.equals(f[0])) {
            return Optional.empty();
        }
        try {
            var response = new HeldReservation(
                    UUID.fromString(f[1]), ReservationStatus.valueOf(f[2]), Instant.parse(f[3]), new BigDecimal(f[4]));
            var request = new HoldRequest(
                    UUID.fromString(f[5]), LocalDate.parse(f[6]), LocalDate.parse(f[7]), Integer.parseInt(f[8]));
            return Optional.of(new Entry(request, response));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
