package ma.nuzul.catalog.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import ma.nuzul.catalog.domain.HotelStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps exactly the V1 {@code hotel} columns; Flyway owns the schema (ddl-auto=validate). */
@Entity
@Table(name = "hotel")
class HotelJpaEntity {

    @Id
    UUID id;

    @Column(name = "owner_sub", nullable = false, updatable = false)
    String ownerSub;

    @Column(nullable = false)
    String name;

    @Column(nullable = false)
    String city;

    String address;

    Short stars;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    String[] amenities;

    @Column(precision = 9, scale = 6)
    BigDecimal lat;

    @Column(precision = 9, scale = 6)
    BigDecimal lon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    HotelStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @Version
    Integer version;

    protected HotelJpaEntity() {}
}
