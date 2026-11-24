package ma.nuzul.catalog;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of a {@code HotelChanged} event on {@code catalog-events}: the hotel was created, edited or had its status
 * changed. Deliberately thin: consumers (the search indexer) re-read the hotel's current state, so an event delivered
 * late or twice can never write stale data.
 */
public record HotelChanged(UUID hotelId, Instant occurredAt) {

    /** The outbox {@code type} column and the Kafka {@code event-type} header. */
    public static final String TYPE = "HotelChanged";
}
