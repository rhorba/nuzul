package ma.nuzul.search.application;

import java.util.Optional;
import java.util.UUID;
import ma.nuzul.catalog.HotelSnapshot;
import ma.nuzul.catalog.HotelSnapshots;
import org.springframework.stereotype.Service;

/**
 * Brings one hotel's document in the aliased index up to date with Postgres (story 3.2): used for every catalog event
 * and by the reindex catch-up. It always re-reads the hotel's current state and never trusts an event's content or
 * timestamp, so an event delivered late, twice or out of order rewrites the current state rather than a stale one.
 *
 * <p>Index failures propagate: the listener's claim rolls back and the record is retried, then dead-lettered.
 */
@Service
public class HotelIndexSync {

    private final HotelSnapshots hotels;
    private final HotelDocumentAssembler assembler;
    private final HotelSearchIndex index;

    public HotelIndexSync(HotelSnapshots hotels, HotelDocumentAssembler assembler, HotelSearchIndex index) {
        this.hotels = hotels;
        this.assembler = assembler;
        this.index = index;
    }

    /** Upserts an active hotel; deletes the document of an inactive (retired) or unknown one. */
    public void sync(UUID hotelId) {
        Optional<HotelSnapshot> hotel = hotels.find(hotelId);
        if (hotel.isPresent() && hotel.get().active()) {
            index.upsert(assembler.assemble(hotel.get()));
        } else {
            index.delete(hotelId);
        }
    }
}
