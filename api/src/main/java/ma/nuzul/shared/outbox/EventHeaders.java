package ma.nuzul.shared.outbox;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

/**
 * The Kafka headers the outbox relay puts on every event, and how a consumer reads them. A missing or malformed
 * header can never become valid on a retry, so it raises {@link InvalidEventHeaderException}, which the listener error
 * handler sends straight to the dead-letter topic.
 */
public final class EventHeaders {

    /** The outbox row id, as a UUID string: what consumers dedupe on ({@link IdempotentConsumer}). */
    public static final String EVENT_ID = "event-id";

    /** The event type, e.g. {@code ReservationHeld}. */
    public static final String EVENT_TYPE = "event-type";

    private static final int MAX_QUOTED_LENGTH = 64;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    /** An event type is a short name such as {@code ReservationHeld}: letters only, so it is safe to log. */
    private static final Pattern EVENT_TYPE_FORMAT = Pattern.compile("[A-Za-z]{1,64}");

    private EventHeaders() {}

    /** @throws InvalidEventHeaderException when the header is missing or not a UUID */
    public static UUID eventId(Headers headers) {
        String value = required(headers, EVENT_ID);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventHeaderException(EVENT_ID + " header is not a UUID: " + abbreviate(value), e);
        }
    }

    /** @throws InvalidEventHeaderException when the header is missing, empty or not a plain name */
    public static String eventType(Headers headers) {
        String value = required(headers, EVENT_TYPE);
        if (!EVENT_TYPE_FORMAT.matcher(value).matches()) {
            throw new InvalidEventHeaderException(EVENT_TYPE + " header is not an event name: " + abbreviate(value));
        }
        return value;
    }

    private static String required(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        if (header == null || header.value() == null || header.value().length == 0) {
            throw new InvalidEventHeaderException(name + " header is missing");
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Keeps a garbage header from flooding or forging log lines: truncated, control characters replaced. */
    private static String abbreviate(String value) {
        String shown = value.length() <= MAX_QUOTED_LENGTH ? value : value.substring(0, MAX_QUOTED_LENGTH) + "...";
        return CONTROL.matcher(shown).replaceAll("?");
    }
}
