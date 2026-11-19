package ma.nuzul.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Maps exactly the V1 {@code room_type} columns. The hotel is a plain id column, not a {@code @ManyToOne}, so the
 * hotel and room-type aggregates stay separate; it is not updatable, so a room type can never change hotel.
 */
@Entity
@Table(name = "room_type")
class RoomTypeJpaEntity {

    @Id
    UUID id;

    @Column(name = "hotel_id", nullable = false, updatable = false)
    UUID hotelId;

    @Column(nullable = false)
    String name;

    @Column(nullable = false)
    Short capacity;

    @Column(name = "bed_config")
    String bedConfig;

    protected RoomTypeJpaEntity() {}
}
