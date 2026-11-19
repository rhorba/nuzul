package ma.nuzul.catalog.adapter.in.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import ma.nuzul.catalog.application.HotelDetails;
import ma.nuzul.catalog.domain.HotelStatus;

/** PUT body: full replacement of the editable fields plus {@code status} (INACTIVE retires the hotel). */
record UpdateHotelRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) String city,
        @Size(max = 500) String address,
        @Min(1) @Max(5) Integer stars,
        @Size(max = 50) List<@NotBlank @Size(max = 50) String> amenities,

        @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6)
        BigDecimal lat,

        @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6)
        BigDecimal lon,

        @NotNull HotelStatus status) {

    HotelDetails toDetails() {
        return new HotelDetails(name, city, address, stars, amenities, lat, lon);
    }
}
