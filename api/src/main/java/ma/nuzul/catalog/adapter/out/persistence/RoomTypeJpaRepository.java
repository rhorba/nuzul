package ma.nuzul.catalog.adapter.out.persistence;

import java.util.Collection;
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

    /** For other modules (RoomTypeOwnership): the room type's hotel id, only when that hotel belongs to the caller. */
    @Query("""
            select r.hotelId from RoomTypeJpaEntity r
            where r.id = :id
              and exists (select 1 from HotelJpaEntity h where h.id = r.hotelId and h.ownerSub = :owner)
            """)
    Optional<UUID> findOwnedHotelId(@Param("id") UUID id, @Param("owner") String ownerSub);

    /** For HotelSnapshots: every room type id of the given hotels in one query, as (hotelId, roomTypeId) pairs. */
    @Query("""
            select r.hotelId as hotelId, r.id as roomTypeId from RoomTypeJpaEntity r
            where r.hotelId in :hotelIds
            order by r.hotelId, r.id
            """)
    List<HotelRoomType> findRoomTypeIdsOf(@Param("hotelIds") Collection<UUID> hotelIds);

    /** Projection of {@link #findRoomTypeIdsOf}. */
    interface HotelRoomType {
        UUID getHotelId();

        UUID getRoomTypeId();
    }

    /** For other modules (BookableRoomTypes): the room type's hotel id, only when that hotel is ACTIVE. */
    @Query("""
            select r.hotelId from RoomTypeJpaEntity r
            where r.id = :id
              and exists (select 1 from HotelJpaEntity h
                          where h.id = r.hotelId and h.status = ma.nuzul.catalog.domain.HotelStatus.ACTIVE)
            """)
    Optional<UUID> findBookableHotelId(@Param("id") UUID id);
}
