package ma.nuzul.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import ma.nuzul.TestcontainersConfiguration;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * Story 3.1: the relay against real Postgres and Kafka, called directly (the scheduled job is off in tests).
 *
 * <p>Isolation: other ITs leave outbox rows behind, so {@link #setUp()} marks every unpublished row as published
 * before each test; each test then only sees its own rows. The consumer is assigned every partition of the topic and
 * positioned at its end before the run, so it reads exactly what this test published.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Timeout(120)
class OutboxRelayIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxRelayStore store;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcTemplate jdbc;

    /** Boot's consumer settings, pointed at the Testcontainers broker by @ServiceConnection. */
    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Value("${app.outbox.max-attempts}")
    int maxAttempts;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE outbox_event SET published_at = now() WHERE published_at IS NULL");
    }

    @AfterEach
    void closeConsumer() {
        if (consumer != null) {
            consumer.close();
        }
    }

    /** Assigned to every partition of {@code topic}, positioned at the current end. */
    private void consumeFromNow(String topic) {
        consumer = consumerFactory.createConsumer("outbox-relay-it-" + UUID.randomUUID(), null);
        List<TopicPartition> partitions = consumer.partitionsFor(topic, WAIT).stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position); // resolve the end offsets now, before anything is published
    }

    /** Polls until {@code done} holds for what has been received, or the wait runs out. */
    private List<ConsumerRecord<String, String>> receive(Predicate<List<ConsumerRecord<String, String>>> done) {
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!done.test(received) && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach(received::add);
        }
        // One more short poll, so a duplicate sent right after the last expected record would be seen too.
        consumer.poll(Duration.ofMillis(500)).forEach(received::add);
        return received;
    }

    private static Predicate<List<ConsumerRecord<String, String>>> containsAll(Collection<UUID> eventIds) {
        return received -> eventIds(received).containsAll(eventIds);
    }

    private static String header(ConsumerRecord<String, String> consumed, String name) {
        return new String(consumed.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static UUID eventId(ConsumerRecord<String, String> consumed) {
        return UUID.fromString(header(consumed, OutboxRelay.EVENT_ID_HEADER));
    }

    private static Set<UUID> eventIds(List<ConsumerRecord<String, String>> received) {
        return received.stream().map(OutboxRelayIT::eventId).collect(Collectors.toSet());
    }

    /** An outbox row created {@code secondsAgo} ago (so the order between rows is explicit). */
    private UUID insert(String aggregate, UUID aggregateId, String type, String payloadJson, int secondsAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_event (id, aggregate, aggregate_id, type, payload, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, now() - make_interval(secs => ?))
                """, id, aggregate, aggregateId, type, payloadJson, secondsAgo);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT published_at, attempts, last_error FROM outbox_event WHERE id = ?", id);
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM outbox_event WHERE id = ?)", Boolean.class, id));
    }

    @Test
    void publishesWithKeyAndHeadersAndMarksRowsPublished() {
        consumeFromNow(Topics.RESERVATION_EVENTS);
        UUID reservationId = UUID.randomUUID();
        UUID eventId = insert("reservation", reservationId, "ReservationHeld", "{\"total\": \"3903.00\"}", 1);

        OutboxRelay.Result result = relay.relay();

        assertThat(result.published()).isEqualTo(1);
        List<ConsumerRecord<String, String>> received = receive(containsAll(List.of(eventId)));
        assertThat(received).hasSize(1);
        ConsumerRecord<String, String> consumed = received.getFirst();
        assertThat(consumed.key()).isEqualTo(reservationId.toString());
        assertThat(eventId(consumed)).isEqualTo(eventId);
        assertThat(header(consumed, OutboxRelay.EVENT_TYPE_HEADER)).isEqualTo("ReservationHeld");
        assertThat(consumed.value()).isEqualTo("{\"total\": \"3903.00\"}");
        assertThat(row(eventId).get("published_at")).isNotNull();
    }

    @Test
    void oneAggregatesEventsArriveInCreationOrder() {
        consumeFromNow(Topics.RESERVATION_EVENTS);
        UUID reservationId = UUID.randomUUID();
        // Inserted out of order on purpose: created_at, not insertion order, decides.
        UUID confirmed = insert("reservation", reservationId, "ReservationConfirmed", "{}", 2);
        UUID held = insert("reservation", reservationId, "ReservationHeld", "{}", 3);
        UUID cancelled = insert("reservation", reservationId, "ReservationCancelled", "{}", 1);

        relay.relay();

        List<ConsumerRecord<String, String>> received = receive(containsAll(List.of(held, confirmed, cancelled)));
        assertThat(received).extracting(OutboxRelayIT::eventId).containsExactly(held, confirmed, cancelled);
        assertThat(received)
                .extracting(ConsumerRecord::partition)
                .containsOnly(received.getFirst().partition());
    }

    @Test
    void aRecordTooLargeIsCountedAndTheRowsBehindItStillPublish() {
        consumeFromNow(Topics.RESERVATION_EVENTS);
        UUID stuckAggregate = UUID.randomUUID();
        String tooLarge = "\"" + "x".repeat(1_500_000) + "\""; // above the producer's 1 MB max.request.size
        UUID poison = insert("reservation", stuckAggregate, "ReservationHeld", tooLarge, 3);
        UUID sameAggregateLater = insert("reservation", stuckAggregate, "ReservationConfirmed", "{}", 2);
        UUID other = insert("reservation", UUID.randomUUID(), "ReservationHeld", "{}", 1);

        OutboxRelay.Result result = relay.relay();

        assertThat(result).isEqualTo(new OutboxRelay.Result(1, 1, OutboxRelay.Stop.NONE));
        Map<String, Object> poisonRow = row(poison);
        assertThat(poisonRow.get("published_at")).isNull();
        assertThat(poisonRow.get("attempts")).isEqualTo(1);
        assertThat((String) poisonRow.get("last_error")).contains("RecordTooLargeException");
        assertThat(row(sameAggregateLater).get("published_at")).isNull();
        assertThat(row(sameAggregateLater).get("attempts")).isEqualTo(0);
        assertThat(row(other).get("published_at")).isNotNull();
        assertThat(eventIds(receive(containsAll(List.of(other))))).containsExactly(other);
    }

    @Test
    void twoConcurrentRelaysPublishEveryEventExactlyOnce() throws Exception {
        consumeFromNow(Topics.RESERVATION_EVENTS);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(insert("reservation", UUID.randomUUID(), "ReservationHeld", "{\"i\": " + i + "}", 200 - i));
        }

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    int published = 0;
                    int last;
                    do {
                        last = relay.relay().published();
                        published += last;
                    } while (last > 0);
                    return published;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get();
            }
            assertThat(total).isEqualTo(200);
        }

        List<ConsumerRecord<String, String>> received = receive(containsAll(ids));
        Map<UUID, Long> perEvent = received.stream()
                .collect(Collectors.groupingBy(OutboxRelayIT::eventId, HashMap::new, Collectors.counting()));
        assertThat(perEvent)
                .hasSize(200)
                .containsOnlyKeys(ids)
                .allSatisfy((id, count) -> assertThat(count).isEqualTo(1L));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class))
                .isZero();
    }

    @Test
    void gaugesReportLagAndParkedRows() {
        assertThat(meters.get(OutboxMetrics.LAG).gauge().value()).isZero();
        assertThat(meters.get(OutboxMetrics.FAILED).gauge().value()).isZero();

        insert("reservation", UUID.randomUUID(), "ReservationHeld", "{}", 3600);
        UUID parked = insert("reservation", UUID.randomUUID(), "ReservationHeld", "{}", 7200);
        jdbc.update("UPDATE outbox_event SET attempts = ? WHERE id = ?", maxAttempts, parked);

        // The parked row is older but no longer counts as lag: only the hour-old row does.
        assertThat(meters.get(OutboxMetrics.LAG).gauge().value()).isBetween(3600.0, 3700.0);
        assertThat(meters.get(OutboxMetrics.FAILED).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void retentionDeletesOnlyRowsPublishedLongerAgoThanRetention() {
        UUID old = insert("reservation", UUID.randomUUID(), "ReservationHeld", "{}", 9 * 86_400);
        jdbc.update("UPDATE outbox_event SET published_at = now() - interval '8 days' WHERE id = ?", old);
        UUID recent = insert("reservation", UUID.randomUUID(), "ReservationHeld", "{}", 9 * 86_400);
        jdbc.update("UPDATE outbox_event SET published_at = now() - interval '1 day' WHERE id = ?", recent);
        UUID unpublished = insert("reservation", UUID.randomUUID(), "ReservationHeld", "{}", 9 * 86_400);
        UUID parked = insert("reservation", UUID.randomUUID(), "ReservationHeld", "{}", 9 * 86_400);
        jdbc.update("UPDATE outbox_event SET attempts = ? WHERE id = ?", maxAttempts, parked);

        int deleted = new OutboxRetentionJob(store, Duration.ofDays(7)).deleteExpired();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(exists(old)).isFalse();
        assertThat(exists(recent)).isTrue();
        assertThat(exists(unpublished)).isTrue();
        assertThat(exists(parked)).isTrue();
    }
}
