package ma.nuzul.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

/** Story 3.1: reading the relay's headers on the consumer side. */
class EventHeadersTest {

    private static Headers headers(String name, String value) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
        return headers;
    }

    @Test
    void readsAValidEventId() {
        UUID id = UUID.randomUUID();

        assertThat(EventHeaders.eventId(headers("event-id", id.toString()))).isEqualTo(id);
    }

    @Test
    void aMissingEventIdIsInvalid() {
        assertThatThrownBy(() -> EventHeaders.eventId(new RecordHeaders()))
                .isInstanceOf(InvalidEventHeaderException.class)
                .hasMessageContaining("event-id header is missing");
    }

    @Test
    void anEmptyEventIdIsInvalid() {
        assertThatThrownBy(() -> EventHeaders.eventId(headers("event-id", "")))
                .isInstanceOf(InvalidEventHeaderException.class);
    }

    @Test
    void aMalformedEventIdIsInvalidAndAbbreviated() {
        assertThatThrownBy(() -> EventHeaders.eventId(headers("event-id", "x".repeat(500))))
                .isInstanceOf(InvalidEventHeaderException.class)
                .hasMessageContaining("not a UUID")
                .hasMessageEndingWith("...");
    }

    @Test
    void readsTheEventTypeAndRejectsAMissingOne() {
        assertThat(EventHeaders.eventType(headers("event-type", "ReservationHeld")))
                .isEqualTo("ReservationHeld");
        assertThatThrownBy(() -> EventHeaders.eventType(new RecordHeaders()))
                .isInstanceOf(InvalidEventHeaderException.class)
                .hasMessageContaining("event-type");
    }

    @Test
    void anEventTypeThatIsNotAPlainNameIsInvalidAndQuotedWithoutControlCharacters() {
        assertThatThrownBy(() -> EventHeaders.eventType(headers("event-type", "Held\nFAKE log line")))
                .isInstanceOf(InvalidEventHeaderException.class)
                .hasMessageContaining("not an event name")
                .hasMessageContaining("Held?FAKE")
                .hasMessageNotContaining("\n");
    }

    @Test
    void aMalformedEventIdIsQuotedWithoutControlCharacters() {
        assertThatThrownBy(() -> EventHeaders.eventId(headers("event-id", "bad\r\nid")))
                .isInstanceOf(InvalidEventHeaderException.class)
                .hasMessageContaining("bad??id");
    }
}
