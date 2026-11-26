package ma.nuzul.inventory;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of a {@code HotelPricesChanged} event on {@code catalog-events}: a room type of the hotel had its rates set.
 * Thin like {@code HotelChanged}: the search indexer re-reads the hotel and its cheapest rate per month, so an event
 * delivered late, twice or out of order can never write a stale price. Keyed by the hotel, like every catalog event,
 * so a hotel's events share one partition; delivery order is not load-bearing (the outbox orders by append time, not
 * commit time), because consumers rebuild from the current state and never apply the payload.
 */
public record HotelPricesChanged(UUID hotelId, Instant occurredAt) {

    /** The outbox {@code type} column and the Kafka {@code event-type} header. */
    public static final String TYPE = "HotelPricesChanged";
}
