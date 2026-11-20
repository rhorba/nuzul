package ma.nuzul.inventory;

import java.util.Map;
import ma.nuzul.shared.error.ConflictException;

/**
 * At least one night of the stay has no room_inventory row or fewer rooms left than requested. A 409 problem+json
 * with code SOLD_OUT through the shared handler. Unchecked, so it rolls back the caller's transaction.
 */
public class SoldOutException extends ConflictException {

    public static final String SOLD_OUT = "SOLD_OUT";

    public SoldOutException() {
        super(SOLD_OUT, "Not enough rooms left for the requested nights", Map.of());
    }
}
