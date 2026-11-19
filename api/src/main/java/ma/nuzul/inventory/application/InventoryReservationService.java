package ma.nuzul.inventory.application;

import java.time.LocalDate;
import java.util.UUID;
import ma.nuzul.inventory.InventoryReservations;
import ma.nuzul.inventory.SoldOutException;
import ma.nuzul.inventory.domain.RoomCount;
import ma.nuzul.inventory.domain.StayRange;
import ma.nuzul.shared.error.DomainValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates the stay and room count, then runs the one guarded UPDATE. Fewer rows updated than nights stayed means
 * a night is missing or full: SOLD_OUT, which rolls back the caller's transaction (and the nights this statement did
 * increment). No retry here: a deadlock (40P01) or any database error propagates unchanged.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class InventoryReservationService implements InventoryReservations {

    private final InventoryRepositoryPort inventory;

    InventoryReservationService(InventoryRepositoryPort inventory) {
        this.inventory = inventory;
    }

    @Override
    public void reserve(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        if (roomTypeId == null) {
            throw new DomainValidationException("roomTypeId is required");
        }
        StayRange stay = new StayRange(checkIn, checkOut);
        RoomCount.requireValid(rooms);

        int updated = inventory.reserve(roomTypeId, stay.checkIn(), stay.checkOut(), rooms);
        if (updated != stay.nights()) {
            throw new SoldOutException();
        }
    }
}
