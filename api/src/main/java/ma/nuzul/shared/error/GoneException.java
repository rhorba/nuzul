package ma.nuzul.shared.error;

import java.util.Map;

/** The resource existed but is no longer usable (410), e.g. a hold that expired before it was paid. */
public class GoneException extends CodedException {

    public GoneException(String code, String message) {
        super(code, message, Map.of());
    }
}
