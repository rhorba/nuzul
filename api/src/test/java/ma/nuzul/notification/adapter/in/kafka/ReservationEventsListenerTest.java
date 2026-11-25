package ma.nuzul.notification.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import ma.nuzul.shared.outbox.IdempotentConsumer;
import ma.nuzul.shared.outbox.InvalidEventHeaderException;
import ma.nuzul.shared.outbox.InvalidEventPayloadException;
import ma.nuzul.shared.outbox.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Story 3.1: the placeholder notification consumer's parsing and its hand-off to IdempotentConsumer. */
class ReservationEventsListenerTest {

    private static final String PAYLOAD = """
            {"reservationId":"%s","hotelId":"%s","roomTypeId":"%s","checkIn":"2026-12-01","checkOut":"2026-12-03",
             "rooms":1,"status":"HELD","totalMad":"1400.00","occurredAt":"2026-11-24T10:00:00Z"}
            """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    private final IdempotentConsumer idempotentConsumer = mock(IdempotentConsumer.class);
    private final ReservationEventsListener listener = new ReservationEventsListener(
            idempotentConsumer, JsonMapper.builder().findAndAddModules().build());

    {
        when(idempotentConsumer.handleOnce(anyString(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return true;
        });
    }

    private static ConsumerRecord<String, String> event(String eventId, String value) {
        ConsumerRecord<String, String> event = new ConsumerRecord<>(
                Topics.RESERVATION_EVENTS, 0, 0L, UUID.randomUUID().toString(), value);
        if (eventId != null) {
            event.headers().add("event-id", eventId.getBytes(StandardCharsets.UTF_8));
        }
        event.headers().add("event-type", "ReservationHeld".getBytes(StandardCharsets.UTF_8));
        return event;
    }

    @Test
    void handsTheEventToIdempotentConsumerUnderTheNotificationName() {
        UUID eventId = UUID.randomUUID();

        listener.onEvent(event(eventId.toString(), PAYLOAD));

        verify(idempotentConsumer).handleOnce(eq("notification"), eq(eventId), any());
    }

    @Test
    void aPayloadThatIsNotAReservationEventIsNotRetryableAndClaimsNothing() {
        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID().toString(), "{not json")))
                .isInstanceOf(InvalidEventPayloadException.class)
                .hasCauseInstanceOf(JacksonException.class)
                .hasMessageNotContaining("not json");
        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID().toString(), "[1, 2]")))
                .isInstanceOf(InvalidEventPayloadException.class)
                .hasCauseInstanceOf(JacksonException.class);
        verify(idempotentConsumer, never()).handleOnce(anyString(), any(), any());
    }

    @Test
    void aTombstoneOrBlankValueIsNotRetryableAndClaimsNothing() {
        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID().toString(), null)))
                .isInstanceOf(InvalidEventPayloadException.class);
        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID().toString(), "  ")))
                .isInstanceOf(InvalidEventPayloadException.class);
        verify(idempotentConsumer, never()).handleOnce(anyString(), any(), any());
    }

    @Test
    void aJacksonFailureInsideTheHandlerIsNotRelabelledAsABadPayload() {
        JacksonException transientFailure = new tools.jackson.core.exc.StreamReadException(null, "provider reply");
        doThrow(transientFailure).when(idempotentConsumer).handleOnce(anyString(), any(), any());

        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID().toString(), PAYLOAD)))
                .isSameAs(transientFailure);
    }

    @Test
    void aPayloadWithoutReservationIdOrStatusIsNotRetryableAndClaimsNothing() {
        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID().toString(), "{\"rooms\":1}")))
                .isInstanceOf(InvalidEventPayloadException.class)
                .hasMessageContaining("lacks reservationId or status");
        assertThatThrownBy(() -> listener.onEvent(
                        event(UUID.randomUUID().toString(), PAYLOAD.replace("\"status\":\"HELD\",", ""))))
                .isInstanceOf(InvalidEventPayloadException.class);
        verify(idempotentConsumer, never()).handleOnce(anyString(), any(), any());
    }

    @Test
    void aMissingEventIdIsNotRetryableAndClaimsNothing() {
        assertThatThrownBy(() -> listener.onEvent(event(null, PAYLOAD)))
                .isInstanceOf(InvalidEventHeaderException.class);
        verify(idempotentConsumer, never()).handleOnce(anyString(), any(), any());
    }
}
