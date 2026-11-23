package ma.nuzul.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.lettuce.core.RedisURI;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The real Lettuce client against a Redis that is not there (closed port) or hangs (a socket that accepts and never
 * answers): the limiter must still answer "allowed" within about half a second, never throw, and never hang. Also
 * the reconnect back-off and the ownership of Lettuce resources.
 */
class LettuceBucketStoreTest {

    static final Duration TIMEOUT = Duration.ofMillis(300);

    /** Generous over the ~0.5 s target so a loaded CI machine does not flake; a hang would blow far past it. */
    static final long MAX_MILLIS = 1_500;

    final RateLimitProperties.Rule rule = new RateLimitProperties.Rule(3, Duration.ofMinutes(1));
    final MutableNanoTime clock = new MutableNanoTime();

    static RedisURI uri(int port) {
        return RedisURI.builder()
                .withHost("localhost")
                .withPort(port)
                .withTimeout(TIMEOUT)
                .build();
    }

    static int closedPort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    LettuceBucketStore store(RedisURI uri) {
        return new LettuceBucketStore(null, uri, TIMEOUT, TIMEOUT, clock);
    }

    static long millisToAnswer(LettuceBucketStore store, RateLimitProperties.Rule rule) {
        var limiter = new RedisRateLimiter(
                Map.of(RateLimiter.RESERVATION_POST, RateLimitConfig.bucketConfiguration(rule)),
                store,
                new SimpleMeterRegistry());
        long start = System.nanoTime();
        var decision = limiter.tryConsume(RateLimiter.RESERVATION_POST, "guest-1");
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(decision.allowed()).isTrue();
        return millis;
    }

    @Test
    @Timeout(10)
    void unreachableRedisFailsOpenFast() throws Exception {
        try (var store = store(uri(closedPort()))) {
            assertThat(millisToAnswer(store, rule)).isLessThan(MAX_MILLIS);
            assertThat(millisToAnswer(store, rule)).isLessThan(MAX_MILLIS);
        }
    }

    @Test
    @Timeout(10)
    void hangingRedisFailsOpenWithinTheTimeout() throws Exception {
        List<Socket> accepted = new ArrayList<>();
        try (var blackhole = new ServerSocket(0)) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(blackhole.accept());
                    }
                } catch (Exception closed) {
                    // the test is over
                }
            });
            try (var store = store(uri(blackhole.getLocalPort()))) {
                assertThat(millisToAnswer(store, rule)).isLessThan(MAX_MILLIS);
                assertThat(millisToAnswer(store, rule)).isLessThan(MAX_MILLIS);
            }
            acceptor.interrupt();
        } finally {
            for (Socket s : accepted) {
                s.close();
            }
        }
    }

    @Test
    @Timeout(10)
    void failedConnectIsNotRetriedWithinTheBackOff() throws Exception {
        var config = RateLimitConfig.bucketConfiguration(rule);
        try (var store = store(uri(closedPort()))) {
            assertThatIllegalStateException().isThrownBy(() -> store.tryConsumeOne("k", config));
            assertThat(store.connectAttempts()).isEqualTo(1);

            clock.advance(LettuceBucketStore.RECONNECT_BACKOFF.minusMillis(1));
            for (int i = 0; i < 5; i++) {
                assertThatIllegalStateException().isThrownBy(() -> store.tryConsumeOne("k", config));
            }
            assertThat(store.connectAttempts()).isEqualTo(1);

            clock.advance(Duration.ofMillis(1));
            assertThatIllegalStateException().isThrownBy(() -> store.tryConsumeOne("k", config));
            assertThat(store.connectAttempts()).isEqualTo(2);
        }
    }

    @Test
    @Timeout(20)
    void sharedClientResourcesSurviveClose() throws Exception {
        var resources = DefaultClientResources.create();
        try {
            new LettuceBucketStore(resources, uri(closedPort()), TIMEOUT, TIMEOUT, clock).close();

            assertThat(resources.eventExecutorGroup().isShuttingDown()).isFalse();
        } finally {
            resources.shutdown();
        }
    }

    @Test
    void callersUriIsNotModified() throws Exception {
        var uri = RedisURI.create("localhost", closedPort());
        Duration before = uri.getTimeout();

        store(uri).close();

        assertThat(uri.getTimeout()).isEqualTo(before);
    }
}
