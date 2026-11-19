package ma.nuzul.shared.error;

/** A domain invariant was violated by the input. */
public class DomainValidationException extends CodedException {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    public DomainValidationException(String message) {
        this(VALIDATION_FAILED, message);
    }

    public DomainValidationException(String code, String message) {
        super(code, message);
    }
}
