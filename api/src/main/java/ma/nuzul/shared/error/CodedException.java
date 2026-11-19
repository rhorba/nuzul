package ma.nuzul.shared.error;

import java.util.Map;

/**
 * Base for business errors that carry a stable, machine-readable {@code code} (exposed as the {@code code}
 * property of the ProblemDetail response). Pure Java so domain code may throw it. Optional {@code properties} are
 * copied as extra ProblemDetail members (e.g. the offending {@code nights}); values must be JSON-friendly.
 */
public abstract class CodedException extends RuntimeException {

    private final String code;
    private final transient Map<String, Object> properties;

    protected CodedException(String code, String message) {
        this(code, message, Map.of());
    }

    protected CodedException(String code, String message, Map<String, Object> properties) {
        super(message);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        /* null-safe: the field is transient, so a deserialized instance has no properties. */
        return properties == null ? Map.of() : properties;
    }
}
