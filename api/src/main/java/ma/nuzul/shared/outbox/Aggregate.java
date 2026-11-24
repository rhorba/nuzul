package ma.nuzul.shared.outbox;

/**
 * The aggregates that publish events, each with the column value stored in {@code outbox_event.aggregate} and the
 * Kafka topic its events go to (system design: {@code reservation-events}, {@code catalog-events}).
 */
public enum Aggregate {
    RESERVATION("reservation", Topics.RESERVATION_EVENTS),
    HOTEL("hotel", Topics.CATALOG_EVENTS),
    ROOM_TYPE("room_type", Topics.CATALOG_EVENTS);

    private final String column;
    private final String topic;

    Aggregate(String column, String topic) {
        this.column = column;
        this.topic = topic;
    }

    public String column() {
        return column;
    }

    public String topic() {
        return topic;
    }

    /** @throws IllegalArgumentException for a value no enum constant stores */
    public static Aggregate fromColumn(String column) {
        for (Aggregate aggregate : values()) {
            if (aggregate.column.equals(column)) {
                return aggregate;
            }
        }
        throw new IllegalArgumentException("unknown outbox aggregate: " + column);
    }
}
