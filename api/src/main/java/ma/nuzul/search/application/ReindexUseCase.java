package ma.nuzul.search.application;

/** In-port: rebuild the hotel search index from Postgres and swap it in behind the alias (story 3.2). */
public interface ReindexUseCase {

    /**
     * Synchronous: returns once the new index is live and caught up.
     *
     * @throws ma.nuzul.shared.error.ConflictException {@code REINDEX_IN_PROGRESS} when another reindex is running
     */
    ReindexResult reindex();
}
