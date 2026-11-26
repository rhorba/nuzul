package ma.nuzul.search;

import static ma.nuzul.support.AdminApi.HOTELS;
import static ma.nuzul.support.AdminApi.MINIMAL_ROOM_TYPE_JSON;
import static ma.nuzul.support.AdminApi.PROBLEM_JSON;
import static ma.nuzul.support.AdminApi.admin;
import static ma.nuzul.support.AdminApi.createHotel;
import static ma.nuzul.support.AdminApi.createRoomType;
import static ma.nuzul.support.AdminApi.guest;
import static ma.nuzul.support.AdminApi.json;
import static ma.nuzul.support.AdminApi.ownerSub;
import static ma.nuzul.support.AdminApi.platformAdmin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.search.application.HotelDocument;
import ma.nuzul.search.application.HotelSearchIndex;
import ma.nuzul.search.application.ReindexUseCase;
import ma.nuzul.search.application.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 3.2: {@code POST /api/v1/admin/search/reindex} against real Postgres and Elasticsearch, in the shared MockMvc
 * test context. The indexer listener stays off here, so every index change is the reindex's own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Timeout(120)
class ReindexIT {

    private static final String REINDEX = "/api/v1/admin/search/reindex";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ElasticsearchClient client;

    @Autowired
    HotelSearchIndex index;

    @Autowired
    SearchProperties properties;

    @Autowired
    Clock clock;

    @Autowired
    ReindexUseCase reindexUseCase;

    @BeforeEach
    void ensureAlias() {
        // The Elasticsearch container is JVM-wide: an earlier IT class may have deleted the alias.
        index.ensureAliasExists();
    }

    @Test
    void rebuildsTheIndexWithActiveHotelsOnlyAndSwapsTheAliasToIt() throws Exception {
        String owner = ownerSub("reindex");
        String activeId = createHotel(mvc, owner, "{\"name\":\"Riad Atlas\",\"city\":\"Marrakech\",\"stars\":4}");
        String roomType = createRoomType(mvc, owner, activeId, MINIMAL_ROOM_TYPE_JSON);
        mvc.perform(json(put("/api/v1/admin/room-types/" + roomType + "/rates"), """
                                [{"night":"%s","priceMad":"640.00","refundable":true}]
                                """.formatted(
                                        LocalDate.now(clock).plusDays(5)))
                        .with(admin(owner)))
                .andExpect(status().isNoContent());
        String retiredId = createHotel(mvc, owner, "{\"name\":\"Dar Fes\",\"city\":\"Fes\"}");
        mvc.perform(json(
                                put(HOTELS + "/" + retiredId),
                                "{\"name\":\"Dar Fes\",\"city\":\"Fes\",\"status\":\"INACTIVE\"}")
                        .with(admin(owner)))
                .andExpect(status().isOk());
        // A stale document for the retired hotel in the live index: the rebuilt index must not carry it over.
        index.upsert(
                new HotelDocument(UUID.fromString(retiredId), "Dar Fes", "Fes", null, null, null, List.of(), Map.of()));
        List<String> before = aliasedIndices();
        int activeHotels = jdbc.queryForObject("SELECT count(*) FROM hotel WHERE status = 'ACTIVE'", Integer.class);

        String body = mvc.perform(post(REINDEX).with(platformAdmin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String newIndex = JsonPath.read(body, "$.index");
        assertThat(newIndex).startsWith(properties.alias() + "-").isNotIn(before);
        assertThat((Integer) JsonPath.read(body, "$.indexed")).isEqualTo(activeHotels);
        // Both hotels had catalog events appended within the last minute: the catch-up re-applies them.
        assertThat((Integer) JsonPath.read(body, "$.caughtUp")).isGreaterThanOrEqualTo(2);
        assertThat(aliasedIndices()).containsExactly(newIndex);
        Map<String, Object> active = document(activeId).orElseThrow();
        assertThat(active).containsEntry("name", "Riad Atlas").containsEntry("stars", 4);
        assertThat(((Map<?, ?>) active.get("minPriceByMonth")).values())
                .singleElement()
                .isEqualTo(640.0);
        assertThat(document(retiredId)).isEmpty();
    }

    /**
     * A hotel renamed and another retired after their page was read and before the catch-up (the edits run on another
     * thread, through the admin API, at the moment the alias is swapped) must show their new state, not the state the
     * pages read. Hooks the swap by putting a decorator in the live reindex service's place for this test only, so the
     * cached context is reused and the service runs exactly as wired (any transaction or proxy included).
     */
    @Test
    void catchUpAppliesEditsMadeAfterThePagesWereRead() throws Exception {
        String owner = ownerSub("reindex");
        String renamedId = createHotel(mvc, owner, "{\"name\":\"Riad Before\",\"city\":\"Rabat\"}");
        String retiredId = createHotel(mvc, owner, "{\"name\":\"Dar Retiring\",\"city\":\"Rabat\"}");
        Runnable editsDuringReindex = () -> {
            try {
                mvc.perform(json(put(HOTELS + "/" + renamedId), """
                                        {"name":"Riad After","city":"Rabat","status":"ACTIVE"}""").with(admin(owner)))
                        .andExpect(status().isOk());
                mvc.perform(json(put(HOTELS + "/" + retiredId), """
                                        {"name":"Dar Retiring","city":"Rabat","status":"INACTIVE"}""").with(admin(owner)))
                        .andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        var hooked = new SwapHook(index, editsDuringReindex);
        Object service = AopTestUtils.getUltimateTargetObject(reindexUseCase);
        Object original = ReflectionTestUtils.getField(service, "index");
        String body;
        try {
            ReflectionTestUtils.setField(service, "index", hooked);
            body = mvc.perform(post(REINDEX).with(platformAdmin()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
        } finally {
            ReflectionTestUtils.setField(service, "index", original);
        }

        // The pages read both hotels before the edits...
        assertThat(hooked.bulked).anySatisfy(d -> {
            assertThat(d.id()).hasToString(renamedId);
            assertThat(d.name()).isEqualTo("Riad Before");
        });
        assertThat(hooked.bulked).anySatisfy(d -> assertThat(d.id()).hasToString(retiredId));
        // ...and the live index shows the state after them: only the catch-up can have written it.
        assertThat(document(renamedId)).hasValueSatisfying(d -> assertThat(d).containsEntry("name", "Riad After"));
        assertThat(document(retiredId)).isEmpty();
        assertThat((Integer) JsonPath.read(body, "$.caughtUp")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aSecondReindexWhileOneHoldsTheLockIsAConflictAndCreatesNoIndex() throws Exception {
        List<String> before = hotelIndices();
        try (Connection running = dataSource.getConnection()) {
            running.setAutoCommit(false);
            try (Statement statement = running.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(hashtext('nuzul.search'), hashtext('reindex'))");
            }

            mvc.perform(post(REINDEX).with(platformAdmin()))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("REINDEX_IN_PROGRESS"));
            assertThat(hotelIndices()).isEqualTo(before);

            running.rollback();
        }

        mvc.perform(post(REINDEX).with(platformAdmin())).andExpect(status().isOk());
    }

    @Test
    void onlyAPlatformAdminMayReindex() throws Exception {
        mvc.perform(post(REINDEX).with(admin(ownerSub("reindex")))).andExpect(status().isForbidden());
        mvc.perform(post(REINDEX).with(guest())).andExpect(status().isForbidden());
        mvc.perform(post(REINDEX)).andExpect(status().isUnauthorized());
    }

    private List<String> aliasedIndices() throws IOException {
        return client.indices().getAlias(g -> g.name(properties.alias())).aliases().keySet().stream()
                .sorted()
                .toList();
    }

    private List<String> hotelIndices() throws IOException {
        return client
                .indices()
                .get(g -> g.index(properties.alias() + "-*").allowNoIndices(true))
                .indices()
                .keySet()
                .stream()
                .sorted()
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> document(String hotelId) throws IOException {
        GetResponse<Map> response = client.get(g -> g.index(properties.alias()).id(hotelId), Map.class);
        return response.found() ? Optional.of((Map<String, Object>) response.source()) : Optional.empty();
    }

    /** Delegates everything; records what was bulk-indexed and runs {@code beforeSwap} on another thread first. */
    static final class SwapHook implements HotelSearchIndex {

        private final HotelSearchIndex delegate;
        private final Runnable beforeSwap;
        final List<HotelDocument> bulked = new CopyOnWriteArrayList<>();

        SwapHook(HotelSearchIndex delegate, Runnable beforeSwap) {
            this.delegate = delegate;
            this.beforeSwap = beforeSwap;
        }

        @Override
        public void upsert(HotelDocument document) {
            delegate.upsert(document);
        }

        @Override
        public void delete(UUID hotelId) {
            delegate.delete(hotelId);
        }

        @Override
        public String createIndex() {
            return delegate.createIndex();
        }

        @Override
        public void bulkIndex(String index, List<HotelDocument> documents) {
            bulked.addAll(documents);
            delegate.bulkIndex(index, documents);
        }

        @Override
        public void switchAliasTo(String index) {
            // Another thread: never inside whatever the reindex itself is running in. A dedicated one rather than the
            // common pool, which other code in the JVM shares and may have saturated.
            ExecutorService editor = Executors.newSingleThreadExecutor();
            try {
                CompletableFuture.runAsync(beforeSwap, editor).join();
            } finally {
                editor.shutdownNow();
            }
            delegate.switchAliasTo(index);
        }

        @Override
        public void deleteIndex(String index) {
            delegate.deleteIndex(index);
        }

        @Override
        public void ensureAliasExists() {
            delegate.ensureAliasExists();
        }
    }
}
