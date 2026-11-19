package ma.nuzul.catalog.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ma.nuzul.catalog.application.RoomTypeDetails;
import ma.nuzul.catalog.domain.RoomType;

/** POST and PUT body (PUT is a full replacement). The parent hotel comes from the path, never the body. */
record RoomTypeRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Min(1) @Max(RoomType.MAX_CAPACITY) Integer capacity,
        @Size(max = 200) String bedConfig) {

    RoomTypeDetails toDetails() {
        return new RoomTypeDetails(name, capacity, bedConfig);
    }
}
