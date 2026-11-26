package ma.nuzul.search.adapter.out.elasticsearch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import ma.nuzul.search.application.HotelDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Story 3.2: a live write that finds the alias missing creates it and retries exactly once; the cases a real cluster
 * cannot easily produce (the alias vanishing again between create and retry) on a mocked client. The happy paths run
 * against Elasticsearch in {@link HotelSearchIndexIT}.
 */
class ElasticsearchMissingAliasRetryTest {

    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
    private final ElasticsearchHotelSearchIndex index =
            new ElasticsearchHotelSearchIndex(client, "hotels", Clock.systemUTC());
    private final HotelDocument hotel =
            new HotelDocument(UUID.randomUUID(), "Riad", "Fes", null, null, null, List.of(), Map.of());

    private static ElasticsearchException error(int status, String type) {
        return new ElasticsearchException(
                "index",
                ErrorResponse.of(r -> r.status(status).error(e -> e.type(type).reason("why"))));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void aliasAppearsToExistOnceChecked() throws IOException {
        when(client.indices()).thenReturn(indices);
        // ensureAliasExists() sees an alias (another instance made it) and creates nothing.
        when(indices.existsAlias(any(Function.class))).thenReturn(new BooleanResponse(true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUpsertThatStillFindsNoIndexAfterTheRetryPropagates() throws IOException {
        var missing = error(404, "index_not_found_exception");
        when(client.index(any(Function.class))).thenThrow(missing);

        assertThatThrownBy(() -> index.upsert(hotel)).isSameAs(missing);

        verify(client, times(2)).index(any(Function.class));
        verify(indices).existsAlias(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDeleteThatStillFindsNoIndexAfterTheRetryPropagates() throws IOException {
        var missing = error(404, "index_not_found_exception");
        when(client.delete(any(Function.class))).thenThrow(missing);

        assertThatThrownBy(() -> index.delete(hotel.id())).isSameAs(missing);

        verify(client, times(2)).delete(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDeleteOfAnAbsentDocumentSucceedsWithoutTouchingTheAlias() throws IOException {
        when(client.delete(any(Function.class))).thenThrow(error(404, "not_found"));

        assertThatCode(() -> index.delete(hotel.id())).doesNotThrowAnyException();

        verify(client, times(1)).delete(any(Function.class));
        verify(indices, never()).existsAlias(any(Function.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anyOtherRejectionIsNotRetried() throws IOException {
        var rejected = error(400, "mapper_parsing_exception");
        when(client.index(any(Function.class))).thenThrow(rejected);

        assertThatThrownBy(() -> index.upsert(hotel)).isSameAs(rejected);

        verify(client, times(1)).index(any(Function.class));
        verify(indices, never()).existsAlias(any(Function.class));
    }
}
