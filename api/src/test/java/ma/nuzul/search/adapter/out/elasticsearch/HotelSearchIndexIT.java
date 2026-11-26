package ma.nuzul.search.adapter.out.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.search.application.HotelDocument;
import ma.nuzul.search.application.HotelSearchIndex;
import ma.nuzul.search.application.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Story 3.2: the hotel index adapter against a real Elasticsearch. Each test starts from no {@code <alias>-*} index at
 * all (startup bootstrap and earlier tests leave some behind), so alias assertions see only what the test did.
 *
 * <p>The Elasticsearch container is JVM-wide (see {@link TestcontainersConfiguration}), so these tests leave the next
 * IT class with no alias at all. Any IT that needs the alias must call {@code ensureAliasExists()} in its own setup
 * rather than rely on the startup bootstrap.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Timeout(120)
class HotelSearchIndexIT {

    private static final String DECEMBER = "2026-12";

    @Autowired
    HotelSearchIndex index;

    @Autowired
    ElasticsearchClient client;

    @Autowired
    SearchProperties properties;

    private String alias;

    @BeforeEach
    void dropAllHotelIndices() throws IOException {
        alias = properties.alias();
        List<String> existing = hotelIndices();
        if (!existing.isEmpty()) {
            client.indices().delete(d -> d.index(existing));
        }
        client.indices().delete(d -> d.index(alias).ignoreUnavailable(true)); // a concrete index named like the alias
    }

    @Test
    void upsertWithoutTheAliasCreatesItAndWritesTheDocument() throws IOException {
        var hotel = hotel("Riad Atlas", null, null, null, Map.of());

        index.upsert(hotel);

        assertThat(adapter().aliasedIndices()).singleElement().asString().startsWith(alias + "-");
        assertThat(hotelIndices()).isEqualTo(adapter().aliasedIndices()); // no concrete index named like the alias
        assertThat(get(hotel.id())).containsEntry("name", "Riad Atlas");
    }

    @Test
    void upsertAfterTheIndicesWereWipedRecreatesTheAlias() throws IOException {
        index.ensureAliasExists();
        index.upsert(hotel("Riad Atlas", null, null, null, Map.of()));
        dropAllHotelIndices(); // data wiped under a running indexer
        var hotel = hotel("Dar Fes", null, null, null, Map.of());

        index.upsert(hotel);

        assertThat(adapter().aliasedIndices()).hasSize(1);
        assertThat(get(hotel.id())).containsEntry("name", "Dar Fes");
    }

    @Test
    void deleteWithoutTheAliasCreatesItAndSucceeds() throws IOException {
        index.ensureAliasExists();
        var hotel = hotel("Riad Atlas", null, null, null, Map.of());
        index.upsert(hotel);
        dropAllHotelIndices();

        assertThatCode(() -> index.delete(hotel.id())).doesNotThrowAnyException();

        assertThat(adapter().aliasedIndices()).hasSize(1);
        assertThat(hotelIndices()).isEqualTo(adapter().aliasedIndices());
        assertThat(exists(hotel.id())).isFalse();
    }

    @Test
    void ensureAliasExistsIgnoresStrayIndicesAndCreatesAFreshOne() throws IOException {
        String stray = index.createIndex();

        index.ensureAliasExists();

        assertThat(adapter().aliasedIndices()).singleElement().isNotEqualTo(stray);
        assertThat(hotelIndices()).hasSize(2).contains(stray);
    }

    @Test
    void switchAliasToWorksWhenNoAliasExistsYet() throws IOException {
        String fresh = index.createIndex();

        index.switchAliasTo(fresh);

        assertThat(adapter().aliasedIndices()).containsExactly(fresh);
        assertThat(hotelIndices()).containsExactly(fresh);
    }

    @Test
    void switchAliasToAlsoDeletesOlderLeftoverIndicesButKeepsNewerAndHandMadeOnes() throws IOException {
        index.ensureAliasExists();
        String live = adapter().aliasedIndices().getFirst();
        String crashedReindex = createEmptyIndex(alias + "-20000101-000000-000"); // older than any index made now
        String newer = createEmptyIndex(alias + "-29991231-235959-999"); // could be a concurrent reindex's: kept
        String handMade = createEmptyIndex(alias + "-broken"); // not a reindex name: kept
        String fresh = index.createIndex();

        index.switchAliasTo(fresh);

        assertThat(adapter().aliasedIndices()).containsExactly(fresh);
        assertThat(hotelIndices())
                .containsExactlyInAnyOrder(fresh, newer, handMade)
                .doesNotContain(live, crashedReindex);
    }

    @Test
    void deleteIndexRemovesAnIndexAndIgnoresAMissingOne() throws IOException {
        String fresh = index.createIndex();

        index.deleteIndex(fresh);

        assertThat(client.indices().exists(e -> e.index(fresh)).value()).isFalse();
        assertThatCode(() -> index.deleteIndex(fresh)).doesNotThrowAnyException();
    }

    @Test
    void deleteIndexRefusesTheIndexBehindTheAlias() throws IOException {
        index.ensureAliasExists();
        String live = adapter().aliasedIndices().getFirst();

        assertThatThrownBy(() -> index.deleteIndex(live)).isInstanceOf(IllegalArgumentException.class);
        assertThat(client.indices().exists(e -> e.index(live)).value()).isTrue();
    }

    @Test
    void bulkIndexSendsEveryChunk() throws IOException {
        String fresh = index.createIndex();
        List<HotelDocument> hotels = IntStream.range(0, 5)
                .mapToObj(i -> hotel("Hotel " + i, null, null, null, Map.of()))
                .toList();

        adapter().bulkIndex(fresh, hotels, 2);

        client.indices().refresh(r -> r.index(fresh));
        assertThat(client.count(c -> c.index(fresh)).count()).isEqualTo(5);
    }

    @Test
    void bulkFailureMessageQuotesTheFirstTenErrorsOnly() throws IOException {
        String broken = createStrictEmptyIndex();
        List<HotelDocument> hotels = IntStream.range(0, 12)
                .mapToObj(i -> hotel("Hotel " + i, null, null, null, Map.of()))
                .toList();

        assertThatThrownBy(() -> index.bulkIndex(broken, hotels))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rejected 12 of 12")
                .hasMessageEndingWith(" and 2 more");
    }

    @Test
    void ensureAliasExistsCreatesOneIndexAndIsIdempotent() throws IOException {
        index.ensureAliasExists();
        List<String> first = adapter().aliasedIndices();

        index.ensureAliasExists();

        assertThat(first).singleElement().asString().startsWith(alias + "-");
        assertThat(adapter().aliasedIndices()).isEqualTo(first);
        assertThat(hotelIndices()).isEqualTo(first);
    }

    @Test
    void racingInstancesEndUpWithExactlyOneIndexBehindTheAlias() throws Exception {
        int racers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            var start = new CountDownLatch(1);
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                // Each racer has its own adapter, as separate app instances would.
                var instance = new ElasticsearchHotelSearchIndex(client, alias, Clock.systemUTC());
                runs.add(pool.submit(() -> {
                    start.await();
                    instance.ensureAliasExists();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(adapter().aliasedIndices()).hasSize(1);
        assertThat(hotelIndices()).isEqualTo(adapter().aliasedIndices());
    }

    @Test
    void upsertStoresTheDocumentWithLocationAndMonthlyPrices() throws IOException {
        index.ensureAliasExists();
        var hotel = hotel("Riad Atlas", 31.6295, -7.9811, 4, Map.of(DECEMBER, new BigDecimal("850.5")));

        index.upsert(hotel);

        Map<String, Object> source = get(hotel.id());
        assertThat(source)
                .containsEntry("id", hotel.id().toString())
                .containsEntry("name", "Riad Atlas")
                .containsEntry("city", "Marrakech")
                .containsEntry("stars", 4)
                .containsEntry("amenities", List.of("wifi", "pool"))
                .containsEntry("location", Map.of("lat", 31.6295, "lon", -7.9811));
        assertThat(source.get("minPriceByMonth")).isEqualTo(Map.of(DECEMBER, 850.50));
    }

    @Test
    void upsertTwiceReplacesTheWholeDocument() throws IOException {
        index.ensureAliasExists();
        var first = hotel("Riad Atlas", 31.6295, -7.9811, 4, Map.of(DECEMBER, new BigDecimal("850.00")));
        index.upsert(first);

        var second =
                new HotelDocument(first.id(), "Riad Atlas & Spa", "Marrakech", null, null, null, List.of(), Map.of());
        index.upsert(second);

        Map<String, Object> source = get(first.id());
        assertThat(source).containsEntry("name", "Riad Atlas & Spa").doesNotContainKeys("location", "stars");
        assertThat(source.get("minPriceByMonth")).isEqualTo(Map.of());
    }

    @Test
    void deleteRemovesTheDocumentAndIgnoresAnAbsentOne() throws IOException {
        index.ensureAliasExists();
        var hotel = hotel("Dar Fes", 34.0331, -5.0003, 3, Map.of());
        index.upsert(hotel);

        index.delete(hotel.id());

        assertThat(exists(hotel.id())).isFalse();
        assertThatCode(() -> index.delete(hotel.id())).doesNotThrowAnyException();
        assertThatCode(() -> index.delete(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void switchAliasMovesTheAliasAndDeletesTheOldIndex() throws IOException {
        index.ensureAliasExists();
        String old = adapter().aliasedIndices().getFirst();
        var orphan = hotel("Closed Hotel", null, null, null, Map.of());
        index.upsert(orphan);

        String fresh = index.createIndex();
        var kept = hotel("Riad Atlas", 31.6295, -7.9811, 4, Map.of(DECEMBER, new BigDecimal("850.00")));
        index.bulkIndex(fresh, List.of(kept));
        index.switchAliasTo(fresh);

        assertThat(fresh).isNotEqualTo(old).startsWith(alias + "-");
        assertThat(adapter().aliasedIndices()).containsExactly(fresh);
        assertThat(client.indices().exists(e -> e.index(old)).value()).isFalse();
        assertThat(exists(kept.id())).isTrue();
        assertThat(exists(orphan.id())).isFalse();
    }

    @Test
    void rangeFilterOnAMonthComparesPricesAsNumbers() throws IOException {
        index.ensureAliasExists();
        var cheap = hotel("Cheap", 31.6, -8.0, 2, Map.of(DECEMBER, new BigDecimal("99.00")));
        var dear = hotel("Dear", 31.6, -8.0, 5, Map.of(DECEMBER, new BigDecimal("1000.00")));
        var otherMonth = hotel("Other", 31.6, -8.0, 3, Map.of("2027-01", new BigDecimal("50.00")));
        List.of(cheap, dear, otherMonth).forEach(index::upsert);
        client.indices().refresh(r -> r.index(alias));

        var response = client.search(
                s -> s.index(alias)
                        .query(q -> q.range(r -> r.number(
                                n -> n.field("minPriceByMonth." + DECEMBER).lte(500.0)))),
                Map.class);

        assertThat(response.hits().hits())
                .extracting(Hit::id)
                .containsExactly(cheap.id().toString());
    }

    @Test
    void strictMappingRejectsAnUnknownField() {
        index.ensureAliasExists();

        assertThatThrownBy(() -> client.index(i -> i.index(alias)
                        .id(UUID.randomUUID().toString())
                        .document(Map.of("id", "x", "name", "x", "city", "x", "rating", 9))))
                .isInstanceOf(ElasticsearchException.class)
                .hasMessageContaining("strict_dynamic_mapping_exception");
    }

    @Test
    void bulkIndexFailsLoudlyWhenADocumentIsRejected() throws IOException {
        String broken = createStrictEmptyIndex();

        assertThatThrownBy(() -> index.bulkIndex(broken, List.of(hotel("Riad", null, null, null, Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rejected 1 of 1")
                .hasMessageContaining("strict_dynamic_mapping_exception");
    }

    @Test
    void bulkIndexOfNothingIsANoOp() {
        String fresh = index.createIndex();

        assertThatCode(() -> index.bulkIndex(fresh, List.of())).doesNotThrowAnyException();
    }

    /** An index that rejects every hotel document (strict mapping, no fields). */
    private String createStrictEmptyIndex() throws IOException {
        String broken = alias + "-broken";
        client.indices()
                .create(c -> c.index(broken)
                        .withJson(new StringReader("{\"mappings\":{\"dynamic\":\"strict\",\"properties\":{}}}")));
        return broken;
    }

    private String createEmptyIndex(String name) throws IOException {
        client.indices().create(c -> c.index(name));
        return name;
    }

    private ElasticsearchHotelSearchIndex adapter() {
        return (ElasticsearchHotelSearchIndex) index;
    }

    private List<String> hotelIndices() throws IOException {
        return client.indices().get(g -> g.index(alias + "-*").allowNoIndices(true)).indices().keySet().stream()
                .sorted()
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(UUID id) throws IOException {
        GetResponse<Map> response = client.get(g -> g.index(alias).id(id.toString()), Map.class);
        assertThat(response.found()).isTrue();
        return (Map<String, Object>) response.source();
    }

    private boolean exists(UUID id) throws IOException {
        return client.exists(e -> e.index(alias).id(id.toString())).value();
    }

    private static HotelDocument hotel(
            String name, Double lat, Double lon, Integer stars, Map<String, BigDecimal> minPriceByMonth) {
        return new HotelDocument(
                UUID.randomUUID(), name, "Marrakech", lat, lon, stars, List.of("wifi", "pool"), minPriceByMonth);
    }
}
