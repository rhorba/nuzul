package ma.nuzul.search;

import static ma.nuzul.support.AdminApi.HOTELS;
import static ma.nuzul.support.AdminApi.MINIMAL_ROOM_TYPE_JSON;
import static ma.nuzul.support.AdminApi.admin;
import static ma.nuzul.support.AdminApi.createHotel;
import static ma.nuzul.support.AdminApi.createRoomType;
import static ma.nuzul.support.AdminApi.json;
import static ma.nuzul.support.AdminApi.ownerSub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.search.application.HotelSearchIndex;
import ma.nuzul.search.application.SearchProperties;
import ma.nuzul.shared.outbox.OutboxRelayDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 3.2: the search indexer end to end. Admin API write, outbox row, relay, {@code catalog-events}, listener,
 * Elasticsearch document read back through the alias.
 *
 * <p>Runs in the shared MockMvc test context (no extra containers). The indexer is off in tests
 * ({@code app.search.listener.enabled=false}); this class starts its container before the first test and stops it
 * after the last, so it never writes to the JVM-wide Elasticsearch while other ITs run. The relay is driven by hand,
 * like {@code IdempotentConsumerIT} does: enabling its schedule would keep it running in the cached context.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Timeout(180)
class SearchIndexerIT {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String INDEXER = "search-indexer";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ElasticsearchClient client;

    @Autowired
    HotelSearchIndex index;

    @Autowired
    SearchProperties properties;

    @Autowired
    Clock clock;

    @Autowired
    ApplicationContext context;

    @BeforeAll
    static void startIndexer(@Autowired KafkaListenerEndpointRegistry registry) {
        registry.getListenerContainer(INDEXER).start();
    }

    @AfterAll
    static void stopIndexer(@Autowired KafkaListenerEndpointRegistry registry) {
        registry.getListenerContainer(INDEXER).stop();
    }

    @BeforeEach
    void ensureAlias() {
        // The Elasticsearch container is JVM-wide: an earlier IT class may have deleted the alias.
        index.ensureAliasExists();
    }

    @Test
    void aCreatedEditedAndPricedHotelIsIndexedWithItsCheapestRatePerMonth() throws Exception {
        String owner = ownerSub("indexer");
        String hotelId = createHotel(mvc, owner, """
                {"name":"Riad Atlas","city":"Marrakech","stars":4,"amenities":["wifi","pool"],
                 "lat":31.6295,"lon":-7.9811}
                """);
        String roomType = createRoomType(mvc, owner, hotelId, MINIMAL_ROOM_TYPE_JSON);
        mvc.perform(json(put(HOTELS + "/" + hotelId), """
                                {"name":"Riad Atlas & Spa","city":"Marrakech","stars":5,"amenities":["spa"],
                                 "lat":31.6295,"lon":-7.9811,"status":"ACTIVE"}
                                """).with(admin(owner))).andExpect(status().isOk());
        LocalDate first = LocalDate.now(clock).plusDays(10);
        LocalDate second = first.plusDays(1);
        mvc.perform(json(put("/api/v1/admin/room-types/" + roomType + "/rates"), """
                                [{"night":"%s","priceMad":"900.00","refundable":true},
                                 {"night":"%s","priceMad":"750.50","refundable":false}]
                                """.formatted(first, second))
                        .with(admin(owner)))
                .andExpect(status().isNoContent());
        Map<String, BigDecimal> expectedPrices = YearMonth.from(first).equals(YearMonth.from(second))
                ? Map.of(YearMonth.from(first).toString(), new BigDecimal("750.50"))
                : Map.of(
                        YearMonth.from(first).toString(), new BigDecimal("900.00"),
                        YearMonth.from(second).toString(), new BigDecimal("750.50"));

        relayAllOf(hotelId);

        await().atMost(WAIT)
                .until(() -> document(hotelId)
                        .map(d -> "Riad Atlas & Spa".equals(d.get("name"))
                                && !prices(d).isEmpty())
                        .orElse(false));
        Map<String, Object> source = document(hotelId).orElseThrow();
        assertThat(source)
                .containsEntry("id", hotelId)
                .containsEntry("city", "Marrakech")
                .containsEntry("stars", 5)
                .containsEntry("amenities", List.of("spa"))
                .containsEntry("location", Map.of("lat", 31.6295, "lon", -7.9811));
        assertThat(prices(source)).isEqualTo(expectedPrices);
    }

    @Test
    void aRetiredHotelIsRemovedFromTheIndex() throws Exception {
        String owner = ownerSub("indexer");
        String hotelId = createHotel(mvc, owner, "{\"name\":\"Dar Fes\",\"city\":\"Fes\"}");
        relayAllOf(hotelId);
        await().atMost(WAIT).until(() -> document(hotelId).isPresent());

        mvc.perform(json(put(HOTELS + "/" + hotelId), "{\"name\":\"Dar Fes\",\"city\":\"Fes\",\"status\":\"INACTIVE\"}")
                        .with(admin(owner)))
                .andExpect(status().isOk());
        relayAllOf(hotelId);

        await().atMost(WAIT).until(() -> document(hotelId).isEmpty());
    }

    /** Relays until every outbox row of the hotel is out (other tests' rows may share the batches). */
    private void relayAllOf(String hotelId) {
        var relay = new OutboxRelayDriver(context);
        await().atMost(WAIT).until(() -> {
            relay.relayOnce();
            return jdbc.queryForObject(
                            "SELECT count(*) FROM outbox_event WHERE payload ->> 'hotelId' = ? AND published_at IS NULL",
                            Integer.class,
                            hotelId)
                    == 0;
        });
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> document(String hotelId) throws IOException {
        GetResponse<Map> response = client.get(g -> g.index(properties.alias()).id(hotelId), Map.class);
        return response.found() ? Optional.of((Map<String, Object>) response.source()) : Optional.empty();
    }

    /** Elasticsearch returns the scaled_float prices as JSON numbers; compared as exact decimals. */
    @SuppressWarnings("unchecked")
    private static Map<String, BigDecimal> prices(Map<String, Object> source) {
        var prices = new TreeMap<String, BigDecimal>();
        ((Map<String, Object>) source.get("minPriceByMonth"))
                .forEach((month, price) -> prices.put(month, new BigDecimal(price.toString()).setScale(2)));
        return prices;
    }
}
