package ma.nuzul.shared.outbox;

/**
 * An event whose value the listener cannot read: empty (a tombstone) or not the expected JSON. Thrown by the
 * listener's own parsing, never by its handler, so it always means the record itself is bad. Not retryable: the
 * record goes straight to its dead-letter topic ({@link KafkaConsumerConfig}).
 */
public class InvalidEventPayloadException extends RuntimeException {

    public InvalidEventPayloadException(String message) {
        super(message);
    }

    public InvalidEventPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
