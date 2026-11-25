package ma.nuzul.shared.outbox;

/** Kafka topic names: constants so {@code @KafkaListener(topics = ...)} can use them. */
public final class Topics {

    public static final String RESERVATION_EVENTS = "reservation-events";
    public static final String CATALOG_EVENTS = "catalog-events";

    /** Where a listener's error handler parks a record it gave up on ({@link KafkaConsumerConfig}). */
    public static final String DEAD_LETTER_SUFFIX = ".DLT";

    private Topics() {}

    /** The dead-letter topic of {@code topic}, e.g. {@code reservation-events.DLT}. */
    public static String deadLetter(String topic) {
        return topic + DEAD_LETTER_SUFFIX;
    }
}
