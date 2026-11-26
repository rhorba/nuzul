package ma.nuzul.search.application;

import java.util.List;
import java.util.UUID;

/**
 * Out-port for the hotel search index (story 3.2). Reads and live writes go through an alias, so a full reindex can
 * build a fresh index beside the live one and swap it in atomically ({@link #createIndex()}, {@link #bulkIndex},
 * {@link #switchAliasTo}) without searches ever seeing a half-built index.
 *
 * <p>Every method throws {@link SearchUnavailableException} when the cluster is unreachable, times out, or answers
 * 429 or 5xx; any other failure is the request's own.
 */
public interface HotelSearchIndex {

    /**
     * Full replace of the hotel's document in the aliased index (fields missing from {@code document} are dropped).
     * When the alias does not exist, creates it as {@link #ensureAliasExists()} does and writes once more, never
     * letting Elasticsearch auto-create an index under its name; throws when that second write fails too.
     */
    void upsert(HotelDocument document);

    /**
     * Removes the hotel from the aliased index; no error when it is not there. Creates a missing alias first, like
     * {@link #upsert}.
     */
    void delete(UUID hotelId);

    /** Creates an empty, unaliased {@code hotels-<UTC timestamp>} index with the hotel mapping; returns its name. */
    String createIndex();

    /**
     * Indexes {@code documents} into {@code index} (not the alias) in chunks; throws at the first chunk with a rejected
     * document (earlier chunks stay indexed).
     */
    void bulkIndex(String index, List<HotelDocument> documents);

    /**
     * Points the alias at {@code index} only and deletes the indices it pointed at before, plus unaliased indices an
     * interrupted earlier reindex left behind (those {@link #createIndex()} made before {@code index}), all in one
     * atomic aliases request: either the swap and the deletes all happen, or none does.
     */
    void switchAliasTo(String index);

    /**
     * Deletes {@code index}; no error when it does not exist. For cleaning up an index {@link #createIndex()} made when
     * filling it failed.
     *
     * @throws IllegalArgumentException when {@code index} is the one behind the alias (deleting it would empty search)
     */
    void deleteIndex(String index);

    /**
     * Creates an index and points the alias at it when the alias does not exist yet; otherwise does nothing. Safe when
     * several instances call it at once: exactly one index ends up behind the alias.
     */
    void ensureAliasExists();
}
