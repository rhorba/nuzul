package ma.nuzul.catalog.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Room types are owner-scoped in SQL through their hotel: matching hotel_id alone is not enough, the hotel must
 * also belong to the caller. The service has already checked the parent; this is the second, independent check.
 */
interface RoomTypeJpaRepository extends JpaRepository<RoomTypeJpaEntity, UUID> {

    @Query("""
            select r from RoomTypeJpaEntity r
            where r.id = :id and r.hotelId = :hotelId
              and exists (select 1 from HotelJpaEntity h where h.id = r.hotelId and h.ownerSub = :owner)
            """)
    Optional<RoomTypeJpaEntity> findScoped(
            @Param("id") UUID id, @Param("hotelId") UUID hotelId, @Param("owner") String ownerSub);

    @Query("""
            select r from RoomTypeJpaEntity r
            where r.hotelId = :hotelId
              and exists (select 1 from HotelJpaEntity h where h.id = r.hotelId and h.ownerSub = :owner)
            order by r.name asc, r.id asc
            """)
    List<RoomTypeJpaEntity> findAllScoped(@Param("hotelId") UUID hotelId, @Param("owner") String ownerSub);

    /** For other modules (RoomTypeOwnership): the room type exists AND its hotel belongs to the caller. */
    @Query("""
            select count(r) > 0 from RoomTypeJpaEntity r
            where r.id = :id
              and exists (select 1 from HotelJpaEntity h where h.id = r.hotelId and h.ownerSub = :owner)
            """)
    boolean existsOwned(@Param("id") UUID id, @Param("owner") String ownerSub);
}
