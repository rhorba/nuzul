package ma.nuzul.search.adapter.out.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.transport.TransportException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import ma.nuzul.search.application.HotelDocument;
import ma.nuzul.search.application.HotelSearchIndex;
import ma.nuzul.search.application.SearchUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link HotelSearchIndex} on the Elasticsearch Java client (story 3.2).
 *
 * <p>The alias carries {@code is_write_index: true} on its one index. Elasticsearch refuses a second write index for
 * an alias, so when two instances race in {@link #ensureAliasExists()} the loser's create (index and alias in one
 * cluster-state update) fails and creates nothing; the loser then sees the winner's alias and stops.
 *
 * <p>Mapping ({@code search/hotels-mapping.json}): {@code minPriceByMonth} is an object whose {@code yyyy-MM} keys a
 * dynamic template maps to {@code scaled_float} (factor 100, i.e. cents), so {@code minPriceByMonth.2026-12} takes
 * numeric range filters. {@code flattened} would store the prices as keywords and compare them as strings ("100" is
 * below "99"); a nested month/price list would need nested queries for the same filter. Prices are written as numbers
 * with two decimals: a search projection of the NUMERIC rates, never read back as money of record.
 *
 * <p>Live writes set {@code require_alias}: with the alias missing, Elasticsearch would otherwise auto-create a
 * concrete index named like the alias, with guessed field types, and every later attempt to create the alias would
 * fail with {@code invalid_alias_name_exception}.
 *
 * <p>A live write that finds the alias missing ({@code index_not_found_exception} on it: Elasticsearch came up after
 * the one-shot startup bootstrap, or its data was wiped) creates it ({@link #ensureAliasExists()}) and retries once;
 * a second failure propagates. This lives here rather than in {@code HotelIndexSync} because "the alias is missing"
 * is only recognisable from Elasticsearch's error type, which the port does not expose, and the race-safe create is
 * already here. The fresh index is empty: documents written before the loss come back with their next change or the
 * next reindex. The reindex path is untouched: {@link #bulkIndex} targets a concrete index.
 *
 * <p>Writes use the default refresh (none): a document shows in searches after the next periodic refresh (about 1 s).
 */
class ElasticsearchHotelSearchIndex implements HotelSearchIndex {

    static final String MAPPING_RESOURCE = "/search/hotels-mapping.json";

    /** Documents per bulk request: keeps each request a few MB at most, whatever the catalog size. */
    static final int BULK_CHUNK_SIZE = 500;

    /** Item errors quoted in a bulk failure message; the rest are only counted. */
    static final int MAX_REPORTED_FAILURES = 10;

    private static final String INDEX_NOT_FOUND = "index_not_found_exception";

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchHotelSearchIndex.class);

    /** Lowercase only, as index names must be: hotels-20261225-143005-123. */
    private static final DateTimeFormatter INDEX_SUFFIX =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final ElasticsearchClient client;
    private final String alias;
    private final Clock clock;
    private final byte[] mapping;
    /** Names {@link #newIndexName()} makes: fixed width, so their string order is their time order. */
    private final Pattern reindexName;

    ElasticsearchHotelSearchIndex(ElasticsearchClient client, String alias, Clock clock) {
        this.client = client;
        this.alias = alias;
        this.clock = clock;
        this.mapping = loadMapping();
        this.reindexName = Pattern.compile(Pattern.quote(alias) + "-\\d{8}-\\d{6}-\\d{3}");
    }

    @Override
    public void upsert(HotelDocument document) {
        creatingMissingAlias(() -> call(() -> client.index(i ->
                i.index(alias).requireAlias(true).id(document.id().toString()).document(source(document)))));
    }

    @Override
    public void delete(UUID hotelId) {
        try {
            creatingMissingAlias(() ->
                    call(() -> client.delete(d -> d.index(alias).id(hotelId.toString()))));
        } catch (ElasticsearchException e) {
            // An absent document is a 404 and fine. An absent index is a 404 too, but only after creating the alias
            // and retrying failed, so it still fails.
            if (e.status() != 404 || isIndexNotFound(e)) {
                throw e;
            }
        }
    }

    /**
     * Runs {@code write} against the alias; when the alias is missing, creates it and runs {@code write} once more. A
     * DELETE never auto-creates an index, and an index request with {@code require_alias} neither, so the first
     * attempt changed nothing.
     */
    private <T> T creatingMissingAlias(Supplier<T> write) {
        try {
            return write.get();
        } catch (ElasticsearchException e) {
            if (!isIndexNotFound(e)) {
                throw e;
            }
            log.warn("Search alias '{}' is missing; creating it and retrying the write once", alias);
            try {
                ensureAliasExists();
            } catch (RuntimeException createFailure) {
                createFailure.addSuppressed(e);
                throw createFailure;
            }
            return write.get();
        }
    }

    private static boolean isIndexNotFound(ElasticsearchException e) {
        return e.status() == 404
                && e.error() != null
                && INDEX_NOT_FOUND.equals(e.error().type());
    }

    @Override
    public String createIndex() {
        String index = newIndexName();
        call(() -> client.indices().create(c -> c.index(index).withJson(mappingStream())));
        return index;
    }

    @Override
    public void bulkIndex(String index, List<HotelDocument> documents) {
        bulkIndex(index, documents, BULK_CHUNK_SIZE);
    }

    /** {@link #bulkIndex(String, List)} with a given chunk size, so tests can cross a chunk boundary cheaply. */
    void bulkIndex(String index, List<HotelDocument> documents, int chunkSize) {
        for (int from = 0; from < documents.size(); from += chunkSize) {
            indexChunk(index, documents.subList(from, Math.min(from + chunkSize, documents.size())), from);
        }
    }

    private void indexChunk(String index, List<HotelDocument> chunk, int offset) {
        var request = new BulkRequest.Builder();
        for (HotelDocument document : chunk) {
            request.operations(op ->
                    op.index(i -> i.index(index).id(document.id().toString()).document(source(document))));
        }
        BulkResponse response = call(() -> client.bulk(request.build()));
        if (response.errors()) {
            List<BulkResponseItem> failed = response.items().stream()
                    .filter(item -> item.error() != null)
                    .toList();
            String quoted = failed.stream()
                    .limit(MAX_REPORTED_FAILURES)
                    .map(ElasticsearchHotelSearchIndex::describe)
                    .collect(Collectors.joining("; "));
            String more = failed.size() > MAX_REPORTED_FAILURES
                    ? " and " + (failed.size() - MAX_REPORTED_FAILURES) + " more"
                    : "";
            throw new IllegalStateException("bulk index into " + index + " rejected " + failed.size() + " of "
                    + chunk.size() + " documents (chunk at offset " + offset + "): " + quoted + more);
        }
    }

    @Override
    public void switchAliasTo(String index) {
        List<String> aliased = aliasedIndices();
        List<String> previous =
                aliased.stream().filter(name -> !name.equals(index)).toList();
        List<String> leftovers = indicesNamedLikeAlias().stream()
                .filter(name -> !name.equals(index) && !aliased.contains(name) && isOlderReindexIndex(name, index))
                .toList();
        if (!leftovers.isEmpty()) {
            log.info("Deleting indices {} left over from an interrupted reindex", leftovers);
        }
        // remove_index deletes the old index inside the same atomic request (taking its alias with it), so a failure
        // can never leave the alias swapped but the old index orphaned. Leftovers of a reindex that died before its
        // swap go in the same request: nothing else would ever delete them.
        call(() -> client.indices().updateAliases(u -> {
            u.actions(a -> a.add(add -> add.index(index).alias(alias).isWriteIndex(true)));
            previous.forEach(old -> u.actions(a -> a.removeIndex(r -> r.index(old))));
            leftovers.forEach(old -> u.actions(a -> a.removeIndex(r -> r.index(old))));
            return u;
        }));
    }

    /**
     * True when both names are ones {@link #newIndexName()} makes and {@code name} was made before {@code index}.
     * Conservative: a newer index could be another reindex still filling (reindex is single-flight under its lock, but
     * a lock lost with its connection would let two overlap), and a hand-made index has unknown purpose; both are kept.
     */
    private boolean isOlderReindexIndex(String name, String index) {
        return reindexName.matcher(name).matches() && reindexName.matcher(index).matches() && name.compareTo(index) < 0;
    }

    @Override
    public void deleteIndex(String index) {
        if (aliasedIndices().contains(index)) {
            throw new IllegalArgumentException("refusing to delete " + index + ": it is behind the alias " + alias);
        }
        call(() -> client.indices().delete(d -> d.index(index).ignoreUnavailable(true)));
    }

    @Override
    public void ensureAliasExists() {
        if (aliasExists()) {
            return;
        }
        List<String> strays = indicesNamedLikeAlias();
        if (!strays.isEmpty()) {
            // Left over from an interrupted reindex or by hand. Not adopted: their content and mapping are unknown.
            // The next reindex's swap deletes the reindex leftovers among them; hand-made ones stay until removed.
            log.warn(
                    "Search alias '{}' is missing but indices {} exist; creating a fresh aliased index", alias, strays);
        }
        String index = newIndexName();
        try {
            call(() -> client.indices()
                    .create(c -> c.index(index).withJson(mappingStream()).aliases(alias, a -> a.isWriteIndex(true))));
        } catch (RuntimeException e) {
            // Another instance won the race: same index name (400), or "alias has more than one write index", which
            // Elasticsearch answers with a 500 the client surfaces as a transport error. Either way the alias now
            // exists.
            boolean exists;
            try {
                exists = aliasExists();
            } catch (RuntimeException checkFailure) {
                checkFailure.addSuppressed(e);
                throw checkFailure;
            }
            if (!exists) {
                throw e;
            }
        }
    }

    /** {@code <alias>-*} indices, aliased or not. */
    private List<String> indicesNamedLikeAlias() {
        return List.copyOf(call(() ->
                        client.indices().get(g -> g.index(alias + "-*").allowNoIndices(true)))
                .indices()
                .keySet());
    }

    /** Index names behind the alias; empty when the alias does not exist. */
    List<String> aliasedIndices() {
        if (!aliasExists()) {
            return List.of();
        }
        return List.copyOf(call(() -> client.indices().getAlias(g -> g.name(alias)))
                .aliases()
                .keySet());
    }

    private boolean aliasExists() {
        return call(() -> client.indices().existsAlias(e -> e.name(alias))).value();
    }

    private String newIndexName() {
        return alias + "-" + INDEX_SUFFIX.format(clock.instant());
    }

    /** The JSON body: {@code location} only when the hotel has coordinates; prices with exactly two decimals. */
    static Map<String, Object> source(HotelDocument document) {
        var source = new LinkedHashMap<String, Object>();
        source.put("id", document.id().toString());
        source.put("name", document.name());
        source.put("city", document.city());
        if (document.lat() != null) {
            source.put("location", Map.of("lat", document.lat(), "lon", document.lon()));
        }
        if (document.stars() != null) {
            source.put("stars", document.stars());
        }
        source.put("amenities", document.amenities());
        var prices = new TreeMap<String, BigDecimal>();
        document.minPriceByMonth()
                .forEach((month, price) -> prices.put(month, price.setScale(2, RoundingMode.HALF_UP)));
        source.put("minPriceByMonth", prices);
        return source;
    }

    private static String describe(BulkResponseItem item) {
        return item.id() + ": " + item.error().type() + " " + item.error().reason();
    }

    private InputStream mappingStream() {
        return new ByteArrayInputStream(mapping);
    }

    private static byte[] loadMapping() {
        try (InputStream in = ElasticsearchHotelSearchIndex.class.getResourceAsStream(MAPPING_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(MAPPING_RESOURCE + " not found on the classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    interface EsCall<T> {
        T run() throws IOException;
    }

    /**
     * Every client call goes through here, which sorts failures into the cluster's fault and the request's:
     *
     * <ul>
     *   <li>{@link SearchUnavailableException}: an {@link IOException} without an HTTP status (connection refused, socket
     *       or connect timeout), or any 429 or 5xx, whether the client parsed an Elasticsearch error body
     *       ({@link ElasticsearchException}) or not ({@link TransportException}).
     *   <li>Unchanged: an {@link ElasticsearchException} with another status (404 missing index or document, 400 mapping
     *       rejection), which callers such as {@link #delete} inspect.
     *   <li>{@link UncheckedIOException}: a {@link TransportException} with another HTTP status.
     * </ul>
     */
    static <T> T call(EsCall<T> call) {
        try {
            return call.run();
        } catch (TransportException e) {
            if (e.statusCode() > 0 && !isUnavailable(e.statusCode())) {
                throw new UncheckedIOException(e);
            }
            throw new SearchUnavailableException("Elasticsearch unavailable: HTTP " + e.statusCode(), e);
        } catch (IOException e) {
            throw new SearchUnavailableException(
                    "Elasticsearch unreachable: " + e.getClass().getSimpleName(), e);
        } catch (ElasticsearchException e) {
            if (isUnavailable(e.status())) {
                throw new SearchUnavailableException("Elasticsearch unavailable: HTTP " + e.status(), e);
            }
            throw e;
        }
    }

    /** 429: the cluster sheds load (e.g. a full write queue); 5xx: it failed. Both pass once it recovers. */
    static boolean isUnavailable(int httpStatus) {
        return httpStatus == 429 || httpStatus >= 500;
    }
}
