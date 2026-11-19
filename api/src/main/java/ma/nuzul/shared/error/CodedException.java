package ma.nuzul.shared.error;

/**
 * Base for business errors that carry a stable, machine-readable {@code code} (exposed as the {@code code}
 * property of the ProblemDetail response). Pure Java so domain code may throw it.
 */
public abstract class CodedException extends RuntimeException {

    private final String code;

    protected CodedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
