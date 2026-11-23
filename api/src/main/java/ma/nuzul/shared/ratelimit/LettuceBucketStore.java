package ma.nuzul.shared.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Bucket4j's compare-and-swap buckets over the limiter's own Lettuce client (on Spring's shared Lettuce
 * {@link ClientResources} when there are some), not Spring's connection factory, so the limiter owns every timeout on
 * the request path:
 *
 * <ul>
 *   <li>The connection is opened asynchronously (started at construction), so the app starts with Redis down and no
 *       request thread is ever stuck in a connect or handshake.
 *   <li>A failed connection is retried by the first call after {@link #RECONNECT_BACKOFF}; calls before that fail at
 *       once, so an outage costs neither a connect attempt per request nor any request latency.
 *   <li>While disconnected, commands are rejected rather than buffered until Redis returns.
 *   <li>Each call has one overall deadline ({@code timeout}) covering the wait for the connection and every round trip
 *       of the CAS; past it the call throws and the limiter fails open.
 * </ul>
 *
 * Buckets expire once they would be full again (plus a second), so an idle guest leaves no key behind: a missing key
 * and a full bucket mean the same thing.
 */
class LettuceBucketStore implements BucketStore, AutoCloseable {

    static final Duration KEEP_AFTER_FULL = Duration.ofSeconds(1);
    static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(5);
    static final RedisCodec<String, byte[]> CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    private final RedisClient client;
    private final RedisURI uri;
    private final Duration timeout;
    private final LongSupplier nanoTime;
    private final AtomicInteger connectAttempts = new AtomicInteger();
    private final AtomicReference<CompletableFuture<StatefulRedisConnection<String, byte[]>>> connection =
            new AtomicReference<>();
    private final AtomicReference<CompletableFuture<AsyncProxyManager<String>>> buckets;
    private volatile long failedAtNanos;

    /**
     * @param resources Spring's shared Lettuce resources, or null to let the client create (and later shut down) its
     *     own; shared resources are never shut down here
     * @param nanoTime monotonic time ({@code System::nanoTime}), so a wall-clock step cannot stall reconnects
     * @param uri the Redis address, with its own (short) timeout for the connection handshake
     */
    LettuceBucketStore(
            @Nullable ClientResources resources,
            RedisURI uri,
            Duration timeout,
            Duration connectTimeout,
            LongSupplier nanoTime) {
        this.uri = uri;
        this.timeout = timeout;
        this.nanoTime = nanoTime;
        this.client = resources == null ? RedisClient.create() : RedisClient.create(resources);
        client.setOptions(ClientOptions.builder()
                .socketOptions(
                        SocketOptions.builder().connectTimeout(connectTimeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        var first = new CompletableFuture<AsyncProxyManager<String>>();
        this.buckets = new AtomicReference<>(first);
        connect(first);
    }

    /** Opens a connection and completes {@code target} with its bucket manager, or with the failure. */
    private void connect(CompletableFuture<AsyncProxyManager<String>> target) {
        connectAttempts.incrementAndGet();
        var conn = client.connectAsync(CODEC, uri).toCompletableFuture();
        connection.set(conn);
        conn.thenApply(this::proxyManagerOrClose).whenComplete((pm, error) -> {
            if (error != null) {
                failedAtNanos = nanoTime.getAsLong();
                target.completeExceptionally(error);
            } else {
                target.complete(pm);
            }
        });
    }

    /** A connection that cannot be wrapped is closed rather than leaked. */
    private AsyncProxyManager<String> proxyManagerOrClose(StatefulRedisConnection<String, byte[]> conn) {
        try {
            return proxyManager(conn);
        } catch (RuntimeException e) {
            conn.closeAsync();
            throw e;
        }
    }

    private AsyncProxyManager<String> proxyManager(StatefulRedisConnection<String, byte[]> conn) {
        return Bucket4jLettuce.casBasedBuilder(conn)
                .expirationAfterWrite(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_FULL))
                .requestTimeout(timeout)
                .build()
                .asAsync();
    }

    @Override
    public ConsumptionProbe tryConsumeOne(String key, BucketConfiguration config) {
        var future = buckets().thenCompose(pm -> pm.builder().build(key, config).tryConsumeAndReturnRemaining(1));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Redis", e);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("Redis did not answer within " + timeout, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Redis call failed: " + e.getCause(), e.getCause());
        }
    }

    /**
     * The current (possibly pending) connection. A failed one is replaced by a new attempt once the back-off has
     * passed, by one caller only; before that the call fails at once.
     */
    private CompletableFuture<AsyncProxyManager<String>> buckets() {
        var current = buckets.get();
        if (!current.isCompletedExceptionally()) {
            return current;
        }
        if (nanoTime.getAsLong() - failedAtNanos < RECONNECT_BACKOFF.toNanos()) {
            throw new IllegalStateException("Redis unavailable, next connection attempt within " + RECONNECT_BACKOFF);
        }
        var retry = new CompletableFuture<AsyncProxyManager<String>>();
        if (!buckets.compareAndSet(current, retry)) {
            return buckets.get();
        }
        connect(retry);
        return retry;
    }

    int connectAttempts() {
        return connectAttempts.get();
    }

    /** Called by Spring on context close, and by tests. Shared {@link ClientResources} are left running. */
    @Override
    public void close() {
        connection.get().thenAccept(StatefulRedisConnection::closeAsync);
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
}
