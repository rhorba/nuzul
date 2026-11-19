package ma.nuzul.catalog.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Owner scope lives in the WHERE clause of every finder, never in a Java comparison after loading. */
interface HotelJpaRepository extends JpaRepository<HotelJpaEntity, UUID> {

    Optional<HotelJpaEntity> findByIdAndOwnerSub(UUID id, String ownerSub);

    Page<HotelJpaEntity> findAllByOwnerSub(String ownerSub, Pageable pageable);
}
