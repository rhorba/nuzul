package ma.nuzul.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.modulith.NamedInterface;

/** The admin-editable fields of a hotel. Owner, id, status and timestamps are never taken from the caller here. */
@NamedInterface("admin")
public record HotelDetails(
        String name,
        String city,
        String address,
        Integer stars,
        List<String> amenities,
        BigDecimal lat,
        BigDecimal lon) {}
