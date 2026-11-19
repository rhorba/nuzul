package ma.nuzul.catalog.application;

import java.math.BigDecimal;
import java.util.List;

/** The admin-editable fields of a hotel. Owner, id, status and timestamps are never taken from the caller here. */
public record HotelDetails(
        String name,
        String city,
        String address,
        Integer stars,
        List<String> amenities,
        BigDecimal lat,
        BigDecimal lon) {}
