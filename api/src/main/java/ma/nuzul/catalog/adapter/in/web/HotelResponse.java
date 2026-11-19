package ma.nuzul.catalog.adapter.in.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import ma.nuzul.catalog.domain.Hotel;
import ma.nuzul.catalog.domain.HotelStatus;

record HotelResponse(
        UUID id,
        String name,
        String city,
        String address,
        Integer stars,
        List<String> amenities,
        BigDecimal lat,
        BigDecimal lon,
        HotelStatus status,
        Integer version) {

    static HotelResponse from(Hotel h) {
        return new HotelResponse(
                h.id(),
                h.name(),
                h.city(),
                h.address(),
                h.stars(),
                h.amenities(),
                h.lat(),
                h.lon(),
                h.status(),
                h.version());
    }
}
