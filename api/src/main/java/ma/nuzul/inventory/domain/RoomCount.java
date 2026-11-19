package ma.nuzul.inventory.domain;

import ma.nuzul.shared.error.DomainValidationException;

/** How many rooms of one room type a single reservation may take: {@value #MIN}..{@value #MAX}. */
public final class RoomCount {

    public static final int MIN = 1;
    public static final int MAX = 9;

    private RoomCount() {}

    /**
     * @return {@code rooms}, unchanged
     * @throws DomainValidationException VALIDATION_FAILED when outside {@value #MIN}..{@value #MAX}
     */
    public static int requireValid(int rooms) {
        if (rooms < MIN || rooms > MAX) {
            throw new DomainValidationException("rooms must be between " + MIN + " and " + MAX);
        }
        return rooms;
    }
}
