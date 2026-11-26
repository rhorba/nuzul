package ma.nuzul.search.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.catalog.HotelChanged;
import ma.nuzul.catalog.HotelSnapshot;
import ma.nuzul.catalog.HotelSnapshots;
import ma.nuzul.catalog.RoomTypeChanged;
import ma.nuzul.inventory.HotelPricesChanged;
import ma.nuzul.inventory.MinPrices;
import ma.nuzul.search.application.HotelDocument;
import ma.nuzul.search.application.HotelDocumentAssembler;
import ma.nuzul.search.application.HotelIndexSync;
import ma.nuzul.search.application.HotelSearchIndex;
import ma.nuzul.shared.outbox.IdempotentConsumer;
import ma.nuzul.shared.outbox.InvalidEventHeaderException;
import ma.nuzul.shared.outbox.InvalidEventPayloadException;
import ma.nuzul.shared.outbox.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Story 3.2: the search indexer's parsing, its hand-off to IdempotentConsumer, and what it writes to the index. */
class CatalogEventsListenerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneId.of("Africa/Casablanca"));

    private final IdempotentConsumer idempotentConsumer = mock(IdempotentConsumer.class);
    private final HotelSnapshots hotels = mock(HotelSnapshots.class);
    private final MinPrices minPrices = mock(MinPrices.class);
    private final HotelSearchIndex index = mock(HotelSearchIndex.class);
    private final CatalogEventsListener listener = new CatalogEventsListener(
            idempotentConsumer,
            new HotelIndexSync(hotels, new HotelDocumentAssembler(minPrices, CLOCK), index),
            JsonMapper.builder().findAndAddModules().build());

    private final UUID hotelId = UUID.randomUUID();
    private final UUID roomTypeId = UUID.randomUUID();

    {
        when(idempotentConsumer.handleOnce(anyString(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return true;
        });
    }

    /** Keyed by the room type on purpose: legacy rows are, and the listener must take the hotel from the payload. */
    private ConsumerRecord<String, String> event(UUID eventId, String type, String value) {
        ConsumerRecord<String, String> event =
                new ConsumerRecord<>(Topics.CATALOG_EVENTS, 0, 0L, roomTypeId.toString(), value);
        if (eventId != null) {
            event.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        }
        event.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        return event;
    }

    private String hotelChanged() {
        return "{\"hotelId\":\"%s\",\"occurredAt\":\"2026-11-24T10:00:00Z\"}".formatted(hotelId);
    }

    private HotelSnapshot snapshot(boolean active) {
        return new HotelSnapshot(
                hotelId, "Riad Atlas", "Marrakech", null, null, 4, List.of("wifi"), active, List.of(roomTypeId));
    }

    @ParameterizedTest
    @ValueSource(strings = {HotelChanged.TYPE, RoomTypeChanged.TYPE, HotelPricesChanged.TYPE})
    void anActiveHotelIsReReadAndUpsertedWithItsMonthlyPrices(String type) {
        UUID eventId = UUID.randomUUID();
        when(hotels.find(hotelId)).thenReturn(Optional.of(snapshot(true)));
        when(minPrices.byMonth(List.of(roomTypeId), LocalDate.of(2026, 11, 26), 12))
                .thenReturn(Map.of(YearMonth.of(2026, 12), new BigDecimal("850.00")));
        String payload = type.equals(RoomTypeChanged.TYPE)
                ? "{\"roomTypeId\":\"%s\",\"hotelId\":\"%s\",\"occurredAt\":\"2026-11-24T10:00:00Z\"}"
                        .formatted(roomTypeId, hotelId)
                : hotelChanged();

        listener.onEvent(event(eventId, type, payload));

        verify(idempotentConsumer).handleOnce(eq("search-indexer"), eq(eventId), any());
        verify(index)
                .upsert(new HotelDocument(
                        hotelId,
                        "Riad Atlas",
                        "Marrakech",
                        null,
                        null,
                        4,
                        List.of("wifi"),
                        Map.of("2026-12", new BigDecimal("850.00"))));
        verify(index, never()).delete(any());
    }

    @Test
    void anInactiveHotelIsDeletedFromTheIndex() {
        when(hotels.find(hotelId)).thenReturn(Optional.of(snapshot(false)));

        listener.onEvent(event(UUID.randomUUID(), HotelChanged.TYPE, hotelChanged()));

        verify(index).delete(hotelId);
        verify(index, never()).upsert(any());
        verifyNoInteractions(minPrices);
    }

    @Test
    void anUnknownHotelIsDeletedFromTheIndex() {
        when(hotels.find(hotelId)).thenReturn(Optional.empty());

        listener.onEvent(event(UUID.randomUUID(), HotelChanged.TYPE, hotelChanged()));

        verify(index).delete(hotelId);
        verify(index, never()).upsert(any());
    }

    @Test
    void occurredAtIsNeverRequired() {
        when(hotels.find(hotelId)).thenReturn(Optional.empty());

        listener.onEvent(event(UUID.randomUUID(), HotelPricesChanged.TYPE, "{\"hotelId\":\"%s\"}".formatted(hotelId)));

        verify(index).delete(hotelId);
    }

    @Test
    void anIndexFailurePropagatesSoTheClaimRollsBackAndTheRecordIsRetried() {
        when(hotels.find(hotelId)).thenReturn(Optional.empty());
        var down = new IllegalStateException("elasticsearch down");
        doThrow(down).when(index).delete(hotelId);

        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID(), HotelChanged.TYPE, hotelChanged())))
                .isSameAs(down);
    }

    @Test
    void anUnknownEventTypeIsSkippedWithoutClaimOrError() {
        assertThatCode(() -> listener.onEvent(event(UUID.randomUUID(), "HotelPhotoAdded", "not even json")))
                .doesNotThrowAnyException();

        verifyNoInteractions(idempotentConsumer, hotels, index);
    }

    @Test
    void aTombstoneOrBlankValueIsNotRetryableAndClaimsNothing() {
        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID(), HotelChanged.TYPE, null)))
                .isInstanceOf(InvalidEventPayloadException.class);
        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID(), HotelChanged.TYPE, "  ")))
                .isInstanceOf(InvalidEventPayloadException.class);
        verify(idempotentConsumer, never()).handleOnce(anyString(), any(), any());
    }

    @Test
    void aPayloadThatIsNotJsonIsNotRetryableAndClaimsNothing() {
        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID(), HotelChanged.TYPE, "{not json")))
                .isInstanceOf(InvalidEventPayloadException.class)
                .hasCauseInstanceOf(JacksonException.class)
                .hasMessageNotContaining("not json");
        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID(), HotelChanged.TYPE, "[1, 2]")))
                .isInstanceOf(InvalidEventPayloadException.class);
        verify(idempotentConsumer, never()).handleOnce(anyString(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"hotelId\":null}", "{\"hotelId\":\"not-a-uuid\"}", "{\"hotelId\":42}"})
    void aMissingOrInvalidHotelIdIsNotRetryableAndClaimsNothing(String payload) {
        assertThatThrownBy(() -> listener.onEvent(event(UUID.randomUUID(), HotelChanged.TYPE, payload)))
                .isInstanceOf(InvalidEventPayloadException.class)
                .hasMessageNotContaining("not-a-uuid");
        verify(idempotentConsumer, never()).handleOnce(anyString(), any(), any());
        verifyNoInteractions(hotels, index);
    }

    @Test
    void aMissingEventIdIsNotRetryableAndClaimsNothing() {
        assertThatThrownBy(() -> listener.onEvent(event(null, HotelChanged.TYPE, hotelChanged())))
                .isInstanceOf(InvalidEventHeaderException.class);
        verify(idempotentConsumer, never()).handleOnce(anyString(), any(), any());
    }

    @Test
    void aDuplicateIsNotReIndexed() {
        doReturn(false).when(idempotentConsumer).handleOnce(anyString(), any(), any());

        listener.onEvent(event(UUID.randomUUID(), HotelChanged.TYPE, hotelChanged()));

        verifyNoInteractions(hotels, index);
        verify(minPrices, never()).byMonth(any(), any(), anyInt());
    }
}
