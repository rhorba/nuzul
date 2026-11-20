package ma.nuzul.reservation.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import ma.nuzul.inventory.domain.RoomCount;
import ma.nuzul.reservation.domain.HoldRequest;

/** POST /reservations body. The bounds agree with the domain (RoomCount, StayRange), which checks them again. */
record HoldReservationRequest(
        @NotNull UUID roomTypeId,
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @NotNull @Min(RoomCount.MIN) @Max(RoomCount.MAX) Integer rooms) {

    HoldRequest toHoldRequest() {
        return new HoldRequest(roomTypeId, checkIn, checkOut, rooms);
    }
}
