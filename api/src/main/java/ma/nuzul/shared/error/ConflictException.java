package ma.nuzul.shared.error;

import java.util.Map;

/** The request is valid but conflicts with the current state of the resource (HTTP 409), e.g. too few rooms left. */
public class ConflictException extends CodedException {

    public ConflictException(String code, String message, Map<String, Object> properties) {
        super(code, message, properties);
    }
}
