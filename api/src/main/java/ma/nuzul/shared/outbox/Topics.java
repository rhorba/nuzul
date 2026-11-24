package ma.nuzul.shared.outbox;

/** Kafka topic names: constants so {@code @KafkaListener(topics = ...)} can use them. */
public final class Topics {

    public static final String RESERVATION_EVENTS = "reservation-events";
    public static final String CATALOG_EVENTS = "catalog-events";

    private Topics() {}
}
