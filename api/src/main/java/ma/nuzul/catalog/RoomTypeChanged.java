package ma.nuzul.catalog;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of a {@code RoomTypeChanged} event on {@code catalog-events}: the room type was created or edited. Thin like
 * {@link HotelChanged}; {@code hotelId} tells a consumer which hotel document to re-read.
 */
public record RoomTypeChanged(UUID roomTypeId, UUID hotelId, Instant occurredAt) {

    /** The outbox {@code type} column and the Kafka {@code event-type} header. */
    public static final String TYPE = "RoomTypeChanged";
}
