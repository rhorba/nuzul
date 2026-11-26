package ma.nuzul.search.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Set;
import java.util.UUID;
import ma.nuzul.catalog.HotelChanged;
import ma.nuzul.catalog.RoomTypeChanged;
import ma.nuzul.inventory.HotelPricesChanged;
import ma.nuzul.search.application.HotelIndexSync;
import ma.nuzul.shared.outbox.EventHeaders;
import ma.nuzul.shared.outbox.IdempotentConsumer;
import ma.nuzul.shared.outbox.InvalidEventPayloadException;
import ma.nuzul.shared.outbox.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Driving adapter: the search indexer (story 3.2). Consumes {@code catalog-events} once per event id (consumer
 * {@value #CONSUMER}) and brings the event's hotel up to date in the aliased index ({@link HotelIndexSync}: re-read,
 * then upsert or delete).
 *
 * <p>The hotel id comes from the payload, never the Kafka key: rows appended before catalog events were keyed by hotel
 * are keyed by their room type. {@code occurredAt} is ignored: the sync rebuilds from the current state, so order and
 * age of events do not matter.
 *
 * <p>Headers and payload are read before the event is claimed, like the notification listener: a bad header, an empty
 * value or a payload without a valid {@code hotelId} ({@link InvalidEventPayloadException}) goes straight to the DLT
 * without touching {@code processed_event}. An event type this version does not know is skipped (debug log, nothing
 * claimed), so a producer can add event types to the topic before every consumer knows them. A failure inside the
 * handler rolls the claim back and is retried: an Elasticsearch outage or a transient Postgres failure until it
 * passes, anything else a few times and then dead-lettered ({@link SearchIndexerKafkaConfig}).
 *
 * <p>{@code app.search.listener.enabled=false} keeps the container from starting (the test default). The container id
 * is {@value #CONSUMER} so a test can start it through the listener registry.
 */
@Component
class CatalogEventsListener {

    /**
     * The Kafka consumer group, the {@code processed_event} dedup namespace and the container id. Do not rename it: a new
     * group has no committed offsets, so with {@code auto-offset-reset: earliest} it replays the topic's whole retention
     * (harmless for the index, which only ever rewrites current state, but a burst of reads and writes), and under a
     * new namespace none of those events counts as processed.
     */
    static final String CONSUMER = "search-indexer";

    static final Set<String> HANDLED_TYPES = Set.of(HotelChanged.TYPE, RoomTypeChanged.TYPE, HotelPricesChanged.TYPE);

    private static final Logger log = LoggerFactory.getLogger(CatalogEventsListener.class);

    private final IdempotentConsumer idempotentConsumer;
    private final HotelIndexSync sync;
    private final JsonMapper json;

    CatalogEventsListener(IdempotentConsumer idempotentConsumer, HotelIndexSync sync, JsonMapper json) {
        this.idempotentConsumer = idempotentConsumer;
        this.sync = sync;
        this.json = json;
    }

    @KafkaListener(
            id = CONSUMER,
            topics = Topics.CATALOG_EVENTS,
            groupId = CONSUMER,
            containerFactory = SearchIndexerKafkaConfig.CONTAINER_FACTORY,
            autoStartup = "${app.search.listener.enabled:true}")
    void onEvent(ConsumerRecord<String, String> event) {
        UUID eventId = EventHeaders.eventId(event.headers());
        String type = EventHeaders.eventType(event.headers());
        if (!HANDLED_TYPES.contains(type)) {
            log.debug("Skipping catalog event {} of unknown type {}", eventId, type);
            return;
        }
        UUID hotelId = parseHotelId(event.value());
        idempotentConsumer.handleOnce(CONSUMER, eventId, () -> sync.sync(hotelId));
    }

    /** The message names the Jackson error type only; the payload itself stays out of the log. */
    private UUID parseHotelId(@Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventPayloadException("catalog event has no value");
        }
        CatalogEventPayload payload;
        try {
            payload = json.readValue(value, CatalogEventPayload.class);
        } catch (JacksonException e) {
            throw new InvalidEventPayloadException(
                    "catalog event has no readable hotelId: " + e.getClass().getSimpleName(), e);
        }
        if (payload == null || payload.hotelId() == null) {
            throw new InvalidEventPayloadException("catalog event lacks hotelId");
        }
        return payload.hotelId();
    }

    /** The one field every catalog event carries; the rest of each payload is not needed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CatalogEventPayload(@Nullable UUID hotelId) {}
}
