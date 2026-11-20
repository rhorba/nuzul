package ma.nuzul.reservation.domain;

import java.time.LocalDate;
import java.util.UUID;
import ma.nuzul.inventory.domain.RoomCount;
import ma.nuzul.inventory.domain.StayRange;
import ma.nuzul.shared.error.DomainValidationException;

/**
 * What a guest asks to hold: {@code rooms} rooms of one room type over the half-open stay [checkIn, checkOut).
 * Validated on construction, before any transaction opens (inventory's rules: 1..30 nights, 1..9 rooms).
 */
public record HoldRequest(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {

    public HoldRequest {
        if (roomTypeId == null) {
            throw new DomainValidationException("roomTypeId is required");
        }
        new StayRange(checkIn, checkOut);
        RoomCount.requireValid(rooms);
    }

    public StayRange stay() {
        return new StayRange(checkIn, checkOut);
    }
}
