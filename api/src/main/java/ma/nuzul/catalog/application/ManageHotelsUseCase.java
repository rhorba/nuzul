package ma.nuzul.catalog.application;

import java.util.UUID;
import ma.nuzul.catalog.domain.Hotel;
import ma.nuzul.catalog.domain.HotelStatus;
import org.springframework.modulith.NamedInterface;

/**
 * In-port: a hotel admin manages their own hotels. {@code ownerSub} is the caller's JWT subject; a hotel owned
 * by someone else behaves exactly like one that does not exist.
 */
@NamedInterface("admin")
public interface ManageHotelsUseCase {

    Hotel create(String ownerSub, HotelDetails details);

    Hotel get(String ownerSub, UUID hotelId);

    Hotel update(String ownerSub, UUID hotelId, HotelDetails details, HotelStatus status);

    PageResult<Hotel> list(String ownerSub, int page, int size);
}
