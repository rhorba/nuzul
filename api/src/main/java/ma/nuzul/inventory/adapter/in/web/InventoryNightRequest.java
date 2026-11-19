package ma.nuzul.inventory.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import ma.nuzul.inventory.domain.InventoryChange;

/** One entry of the PUT body: the total rooms sellable on one night. */
record InventoryNightRequest(
        @NotNull LocalDate night,
        @NotNull @Min(0) @Max(InventoryChange.MAX_TOTAL) Integer total) {

    InventoryChange toChange() {
        return new InventoryChange(night, total);
    }
}
