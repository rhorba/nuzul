package ma.nuzul.notification.adapter.in.kafka;

import java.util.UUID;
import ma.nuzul.reservation.ReservationEvent;
import ma.nuzul.shared.outbox.EventHeaders;
import ma.nuzul.shared.outbox.IdempotentConsumer;
import ma.nuzul.shared.outbox.InvalidEventPayloadException;
import ma.nuzul.shared.outbox.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Driving adapter: consumes {@code reservation-events} once per event id (consumer {@value #CONSUMER}) and, for now,
 * only logs what it would send. Placeholder proving the consumer side of the outbox (dedup, retry, DLT); story 3.4
 * replaces the log line with the real email handler.
 *
 * <p>Headers and payload are read before the event is claimed: a bad header ({@code InvalidEventHeaderException}), an
 * empty value (tombstone) or a payload that is not a {@link ReservationEvent} ({@link InvalidEventPayloadException})
 * (or lacks its reservationId or status) goes straight to the DLT without touching {@code processed_event}. Only this parsing step is wrapped: a Jackson
 * failure inside the handler stays retryable.
 *
 * <p>{@code app.notification.listener.enabled=false} keeps the container from starting (the test default, so ITs that
 * publish placeholder payloads do not dead-letter them here).
 */
@Component
class ReservationEventsListener {

    /**
     * Both the Kafka consumer group and the {@code processed_event} dedup namespace. Do not rename it: a new group has
     * no committed offsets, so with {@code auto-offset-reset: earliest} it replays the topic's whole retention, and
     * under a new namespace none of those events counts as processed, so every one would be handled (emailed) again.
     * Story 3.4 must keep this name, or guard the replay by event age, when it brings the real email handler.
     */
    static final String CONSUMER = "notification";

    private static final Logger log = LoggerFactory.getLogger(ReservationEventsListener.class);

    private final IdempotentConsumer idempotentConsumer;
    private final JsonMapper json;

    ReservationEventsListener(IdempotentConsumer idempotentConsumer, JsonMapper json) {
        this.idempotentConsumer = idempotentConsumer;
        this.json = json;
    }

    @KafkaListener(
            topics = Topics.RESERVATION_EVENTS,
            groupId = CONSUMER,
            autoStartup = "${app.notification.listener.enabled:true}")
    void onEvent(ConsumerRecord<String, String> event) {
        UUID eventId = EventHeaders.eventId(event.headers());
        String type = EventHeaders.eventType(event.headers());
        ReservationEvent reservation = parse(event.value());
        idempotentConsumer.handleOnce(
                CONSUMER, eventId, () -> log.info("would notify reservation {} {}", reservation.reservationId(), type));
    }

    /** The message names the Jackson error type only; the payload itself stays out of the log. */
    private ReservationEvent parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventPayloadException("reservation event has no value");
        }
        ReservationEvent event;
        try {
            event = json.readValue(value, ReservationEvent.class);
        } catch (JacksonException e) {
            throw new InvalidEventPayloadException(
                    "reservation event is not a ReservationEvent: "
                            + e.getClass().getSimpleName(),
                    e);
        }
        if (event.reservationId() == null || event.status() == null) {
            throw new InvalidEventPayloadException("reservation event lacks reservationId or status");
        }
        return event;
    }
}
