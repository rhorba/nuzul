package ma.nuzul.shared.outbox;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.ReserveFixtures;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 3.1: the consumer side of the outbox against real Postgres and Kafka. Listeners run as in production: the
 * notification listener on {@code reservation-events}, and {@link ProbeListenerConfig}'s probe on its own topic for the
 * failure cases (a handler told to fail, with counters). Both go through the production error handler and
 * {@link IdempotentConsumer}.
 *
 * <p>The notification listener is off by default in tests; this class turns it on.
 *
 * <p>Isolation: every test uses fresh keys and event ids and only ever asserts on those, since other ITs publish to
 * {@code reservation-events} too.
 */
@Import({TestcontainersConfiguration.class, ProbeListenerConfig.class})
@SpringBootTest(properties = "app.notification.listener.enabled=true")
@AutoConfigureMockMvc
@Timeout(120)
class IdempotentConsumerIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxRelay relay;

    @Autowired
    ProbeListenerConfig.ProbeListener probe;

    private Consumer<String, String> deadLetterConsumer;

    @AfterEach
    void closeConsumer() {
        if (deadLetterConsumer != null) {
            deadLetterConsumer.close();
        }
    }

    private void send(String topic, String key, UUID eventId, String value) throws Exception {
        ProducerRecord<String, String> event = new ProducerRecord<>(topic, key, value);
        if (eventId != null) {
            event.headers().add(EventHeaders.EVENT_ID, eventId.toString().getBytes(StandardCharsets.UTF_8));
        }
        event.headers().add(EventHeaders.EVENT_TYPE, "ReservationHeld".getBytes(StandardCharsets.UTF_8));
        kafka.send(event).get();
    }

    private static String reservationEventJson(UUID reservationId) {
        return """
                {"reservationId":"%s","hotelId":"%s","roomTypeId":"%s","checkIn":"2026-12-01",
                 "checkOut":"2026-12-03","rooms":1,"status":"HELD","totalMad":"1400.00",
                 "occurredAt":"2026-11-24T10:00:00Z"}
                """.formatted(reservationId, UUID.randomUUID(), UUID.randomUUID());
    }

    private int processedRows(String consumer, UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = ? AND event_id = ?",
                Integer.class,
                consumer,
                eventId);
    }

    /** Reads the whole DLT of {@code topic} from the start, until a record with {@code key} shows up or time runs out. */
    private Optional<ConsumerRecord<String, String>> deadLetter(String topic, String key, Duration wait) {
        String dlt = Topics.deadLetter(topic);
        if (deadLetterConsumer == null) {
            deadLetterConsumer = consumerFactory.createConsumer("idempotent-consumer-it-" + UUID.randomUUID(), null);
            List<TopicPartition> partitions = deadLetterConsumer.partitionsFor(dlt, WAIT).stream()
                    .map(info -> new TopicPartition(dlt, info.partition()))
                    .toList();
            deadLetterConsumer.assign(partitions);
            deadLetterConsumer.seekToBeginning(partitions);
        }
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            deadLetterConsumer.poll(Duration.ofMillis(200)).forEach(received::add);
            Optional<ConsumerRecord<String, String>> found =
                    received.stream().filter(r -> key.equals(r.key())).findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static String header(ConsumerRecord<String, String> consumed, String name) {
        var header = consumed.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void aDuplicateDeliveryIsHandledOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID duplicate = UUID.randomUUID();
        UUID sentinel = UUID.randomUUID();

        send(ProbeListenerConfig.TOPIC, key, duplicate, "{}");
        send(ProbeListenerConfig.TOPIC, key, duplicate, "{}");
        // Same key, so same partition and after both duplicates: once it is handled, both duplicates were consumed.
        send(ProbeListenerConfig.TOPIC, key, sentinel, "{}");

        await().atMost(WAIT).until(() -> probe.handled(sentinel) == 1);
        assertThat(probe.deliveries(key)).isEqualTo(3);
        assertThat(probe.handled(duplicate)).isEqualTo(1);
        assertThat(processedRows(ProbeListenerConfig.CONSUMER, duplicate)).isEqualTo(1);
    }

    @Test
    void theNotificationConsumerRecordsADuplicateReservationEventOnce() throws Exception {
        UUID reservationId = UUID.randomUUID();
        String key = reservationId.toString();
        UUID duplicate = UUID.randomUUID();
        UUID sentinel = UUID.randomUUID();

        send(Topics.RESERVATION_EVENTS, key, duplicate, reservationEventJson(reservationId));
        send(Topics.RESERVATION_EVENTS, key, duplicate, reservationEventJson(reservationId));
        send(Topics.RESERVATION_EVENTS, key, sentinel, reservationEventJson(reservationId));

        await().atMost(WAIT).until(() -> processedRows("notification", sentinel) == 1);
        assertThat(processedRows("notification", duplicate)).isEqualTo(1);
    }

    @Test
    void aHandlerThatFailsTwiceIsRetriedAndHandledOnceWithoutDeadLettering() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID eventId = UUID.randomUUID();
        probe.failTimes(key, 2);

        send(ProbeListenerConfig.TOPIC, key, eventId, "{}");

        await().atMost(WAIT).until(() -> probe.handled(eventId) == 1);
        assertThat(probe.deliveries(key)).isEqualTo(3);
        assertThat(processedRows(ProbeListenerConfig.CONSUMER, eventId)).isEqualTo(1);
        assertThat(deadLetter(ProbeListenerConfig.TOPIC, key, Duration.ofSeconds(2)))
                .isEmpty();
    }

    @Test
    void aHandlerThatAlwaysFailsIsRetriedThenDeadLetteredWithItsOriginalRecord() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID eventId = UUID.randomUUID();
        probe.failTimes(key, Integer.MAX_VALUE);

        send(ProbeListenerConfig.TOPIC, key, eventId, "{\"poison\":true}");

        ConsumerRecord<String, String> dead =
                deadLetter(ProbeListenerConfig.TOPIC, key, WAIT).orElseThrow();
        assertThat(dead.value()).isEqualTo("{\"poison\":true}");
        assertThat(header(dead, EventHeaders.EVENT_ID)).isEqualTo(eventId.toString());
        assertThat(header(dead, EventHeaders.EVENT_TYPE)).isEqualTo("ReservationHeld");
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(ProbeListenerConfig.TOPIC);
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(IllegalStateException.class.getName());
        // Exception messages and stack traces can carry PII: only class names go to the DLT.
        assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .isNull();
        assertThat(dead.headers())
                .extracting(org.apache.kafka.common.header.Header::key)
                .noneMatch(name -> name.contains("message") || name.contains("stacktrace"));
        assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE))
                .isNull();
        assertThat(probe.deliveries(key)).isEqualTo(1 + KafkaConsumerConfig.MAX_RETRIES);
        assertThat(probe.handled(eventId)).isZero();
        // Every attempt rolled its claim back: a replay from the DLT would be handled.
        assertThat(processedRows(ProbeListenerConfig.CONSUMER, eventId)).isZero();
    }

    @Test
    void aRecordWithoutAnEventIdIsDeadLetteredWithoutRetries() throws Exception {
        String key = UUID.randomUUID().toString();

        send(ProbeListenerConfig.TOPIC, key, null, "{}");

        ConsumerRecord<String, String> dead =
                deadLetter(ProbeListenerConfig.TOPIC, key, WAIT).orElseThrow();
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(InvalidEventHeaderException.class.getName());
        assertThat(probe.deliveries(key)).isEqualTo(1);
    }

    @Test
    void aTombstoneOnReservationEventsIsDeadLetteredAsAnInvalidPayloadAndNotClaimed() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID eventId = UUID.randomUUID();

        send(Topics.RESERVATION_EVENTS, key, eventId, null);

        ConsumerRecord<String, String> dead =
                deadLetter(Topics.RESERVATION_EVENTS, key, WAIT).orElseThrow();
        assertThat(dead.value()).isNull();
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(InvalidEventPayloadException.class.getName());
        assertThat(processedRows("notification", eventId)).isZero();
    }

    @Test
    void aHeldReservationReachesTheNotificationConsumerThroughTheRelay() throws Exception {
        var fx = new ReserveFixtures(jdbc);
        UUID roomType = fx.roomType("owner-" + UUID.randomUUID());
        for (int i = 0; i < 2; i++) {
            fx.inventory(roomType, night(i), 3, 0);
            fx.rate(roomType, night(i), "700.00");
        }
        mvc.perform(json(post("/api/v1/reservations"), """
                                {"roomTypeId":"%s","checkIn":"%s","checkOut":"%s","rooms":1}
                                """.formatted(roomType, night(0), night(2)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(guest("guest-" + UUID.randomUUID())))
                .andExpect(status().isCreated());
        UUID eventId = jdbc.queryForObject(
                "SELECT id FROM outbox_event WHERE payload ->> 'roomTypeId' = ?", UUID.class, roomType.toString());

        // Other tests' rows may be published in the same runs; keep going until this one is out.
        await().atMost(WAIT).until(() -> {
            relay.relay();
            return jdbc.queryForObject(
                    "SELECT published_at IS NOT NULL FROM outbox_event WHERE id = ?", Boolean.class, eventId);
        });

        await().atMost(WAIT).until(() -> processedRows("notification", eventId) == 1);
    }
}
