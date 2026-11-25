package ma.nuzul.shared.outbox;

/**
 * An event without a usable {@code event-id} or {@code event-type} header. Not retryable: the record goes straight to
 * its dead-letter topic ({@link KafkaConsumerConfig}).
 */
public class InvalidEventHeaderException extends RuntimeException {

    public InvalidEventHeaderException(String message) {
        super(message);
    }

    public InvalidEventHeaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
