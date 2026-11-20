package ma.nuzul.shared.error;

import java.util.Map;

/** A well-formed request the server refuses to act on (422), e.g. an idempotency key reused for another request. */
public class UnprocessableException extends CodedException {

    public UnprocessableException(String code, String message, Map<String, Object> properties) {
        super(code, message, properties);
    }
}
