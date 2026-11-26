package ma.nuzul.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Catalog's published API for search (story 3.2): the indexer re-reads a hotel here for every catalog event instead
 * of trusting the event's content, and pages through every ACTIVE hotel for a full reindex.
 */
public interface HotelSnapshots {

    /** The hotel whatever its status (the indexer deletes the document of an inactive one); empty when unknown. */
    Optional<HotelSnapshot> find(UUID hotelId);

    /**
     * The next {@code limit} ACTIVE hotels with an id greater than {@code afterId} (null: from the start), ascending
     * by id. Keyset paging: a hotel created or retired during a reindex never shifts the pages after it, unlike an
     * offset. An empty or short page means the end.
     *
     * @throws IllegalArgumentException when {@code limit} is not positive
     */
    List<HotelSnapshot> activePage(@Nullable UUID afterId, int limit);
}
