package ma.nuzul.shared.error;

/** A resource does not exist or is outside the caller's scope (deliberately indistinguishable: no existence leak). */
public class NotFoundException extends CodedException {

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
