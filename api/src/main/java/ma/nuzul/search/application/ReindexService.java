package ma.nuzul.search.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.nuzul.catalog.HotelSnapshot;
import ma.nuzul.catalog.HotelSnapshots;
import ma.nuzul.shared.error.ConflictException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Full reindex (story 3.2): build a fresh index from every ACTIVE hotel, swap the alias to it, then catch up.
 *
 * <p><b>One at a time.</b> The whole run holds a non-blocking lock ({@link ReindexLockPort#runExclusively}); a second
 * request, on any replica, gets 409 {@value #REINDEX_IN_PROGRESS} instead of building a competing index.
 *
 * <p><b>No transaction here, on purpose.</b> Every catalog and price read runs in its own short read-only transaction
 * (as the adapters declare). One transaction around the whole run would share one JPA persistence context: the
 * catch-up's {@code find} would return the entity cached when its page was read, re-writing a hotel renamed or retired
 * during the reindex with its stale state, and every hotel of the catalog would stay managed until commit. The lock is
 * therefore held on its own connection, outside those reads.
 *
 * <p><b>Catch-up.</b> The live indexer keeps writing through the alias while the new index fills, and the alias still
 * points at the old index then. A hotel edited after its page was read is written to the old index only, which the
 * swap deletes: without help, that edit would be lost until the hotel changes again. So after the swap, every hotel
 * with a catalog event appended since {@value #CATCH_UP_MARGIN_MINUTES} minute before the start is re-read and
 * written again through the alias, exactly as the listener would. The start is database time, like the outbox's
 * {@code created_at}. The margin covers transactions that appended their event before the start but committed
 * (became visible to the page reads) only later. Re-applying a hotel whose page already had the latest state is
 * harmless: it writes the same current state.
 *
 * <p><b>Failure.</b> Before the swap the alias is untouched: the new index is deleted (a cleanup error is attached to
 * the original as suppressed) and the error propagates. After the swap the new index is live and complete up to the
 * pages; a catch-up failure is logged and propagates, and the listener's own retries or another reindex fix it.
 */
@Service
class ReindexService implements ReindexUseCase {

    static final String REINDEX_IN_PROGRESS = "REINDEX_IN_PROGRESS";

    /** Hotels per catalog read and per bulk call. */
    static final int PAGE_SIZE = 500;

    static final long CATCH_UP_MARGIN_MINUTES = 1;
    private static final Duration CATCH_UP_MARGIN = Duration.ofMinutes(CATCH_UP_MARGIN_MINUTES);

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final ReindexLockPort lock;
    private final HotelSnapshots hotels;
    private final HotelDocumentAssembler assembler;
    private final HotelSearchIndex index;
    private final HotelIndexSync sync;
    private final RecentCatalogChangesPort changes;

    ReindexService(
            ReindexLockPort lock,
            HotelSnapshots hotels,
            HotelDocumentAssembler assembler,
            HotelSearchIndex index,
            HotelIndexSync sync,
            RecentCatalogChangesPort changes) {
        this.lock = lock;
        this.hotels = hotels;
        this.assembler = assembler;
        this.index = index;
        this.sync = sync;
        this.changes = changes;
    }

    @Override
    public ReindexResult reindex() {
        return lock.runExclusively(this::rebuild)
                .orElseThrow(() ->
                        new ConflictException(REINDEX_IN_PROGRESS, "A search reindex is already running", Map.of()));
    }

    private ReindexResult rebuild() {
        Instant start = changes.databaseNow();
        String newIndex = index.createIndex();
        int indexed;
        try {
            indexed = fill(newIndex);
            index.switchAliasTo(newIndex);
        } catch (RuntimeException e) {
            try {
                index.deleteIndex(newIndex);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        int caughtUp = catchUp(newIndex, start);
        log.info("Reindexed {} hotels into {}, caught up {}", indexed, newIndex, caughtUp);
        return new ReindexResult(newIndex, indexed, caughtUp);
    }

    /** Keyset pages of ACTIVE hotels into {@code newIndex}; a short or empty page is the last one. */
    private int fill(String newIndex) {
        int indexed = 0;
        @Nullable UUID afterId = null;
        while (true) {
            List<HotelSnapshot> page = hotels.activePage(afterId, PAGE_SIZE);
            if (!page.isEmpty()) {
                List<HotelDocument> documents = new ArrayList<>(page.size());
                page.forEach(hotel -> documents.add(assembler.assemble(hotel)));
                index.bulkIndex(newIndex, documents);
                indexed += documents.size();
                afterId = page.getLast().id();
            }
            if (page.size() < PAGE_SIZE) {
                return indexed;
            }
        }
    }

    private int catchUp(String newIndex, Instant start) {
        try {
            List<UUID> changed = changes.hotelIdsChangedSince(start.minus(CATCH_UP_MARGIN));
            changed.forEach(sync::sync);
            return changed.size();
        } catch (RuntimeException e) {
            log.error(
                    "Reindex swapped the alias to {} but its catch-up failed; edits made during the reindex may be"
                            + " missing until those hotels change again or the next reindex",
                    newIndex,
                    e);
            throw e;
        }
    }
}
