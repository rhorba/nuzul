package ma.nuzul.catalog.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.catalog.domain.HotelStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Owner scope lives in the WHERE clause of every admin finder, never in a Java comparison after loading. The keyset
 * finders serve the search read API, which is not owner-scoped.
 */
interface HotelJpaRepository extends JpaRepository<HotelJpaEntity, UUID> {

    Optional<HotelJpaEntity> findByIdAndOwnerSub(UUID id, String ownerSub);

    Page<HotelJpaEntity> findAllByOwnerSub(String ownerSub, Pageable pageable);

    /* Keyset pages for HotelSnapshots (not owner-scoped: search reads every hotel). Ordered by the primary key, so
     * each page is an index range scan however deep the reindex is. */
    List<HotelJpaEntity> findByStatusOrderByIdAsc(HotelStatus status, Limit limit);

    List<HotelJpaEntity> findByStatusAndIdGreaterThanOrderByIdAsc(HotelStatus status, UUID afterId, Limit limit);
}
