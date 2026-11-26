package ma.nuzul.search.adapter.out.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.transport.TransportException;
import co.elastic.clients.transport.http.TransportHttpClient;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import ma.nuzul.search.application.SearchUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Story 3.2: which client failures count as an Elasticsearch outage (retried until the cluster is back). */
class ElasticsearchCallClassificationTest {

    private static TransportException transport(int status) {
        TransportHttpClient.Response response = mock(TransportHttpClient.Response.class);
        when(response.statusCode()).thenReturn(status);
        return new TransportException(response, "HTTP " + status, "index");
    }

    private static ElasticsearchException elasticsearch(int status) {
        return new ElasticsearchException(
                "index",
                ErrorResponse.of(r ->
                        r.status(status).error(e -> e.type("some_exception").reason("why"))));
    }

    @Test
    void aSuccessfulCallReturnsItsResult() {
        assertThat(ElasticsearchHotelSearchIndex.call(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void connectionFailuresAndTimeoutsAreAnOutage() {
        assertThatThrownBy(() -> ElasticsearchHotelSearchIndex.call(() -> {
                    throw new ConnectException("Connection refused");
                }))
                .isInstanceOf(SearchUnavailableException.class)
                .hasCauseInstanceOf(ConnectException.class);
        assertThatThrownBy(() -> ElasticsearchHotelSearchIndex.call(() -> {
                    throw new SocketTimeoutException("Read timed out");
                }))
                .isInstanceOf(SearchUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503, 504})
    void overloadAndServerErrorsAreAnOutageWithOrWithoutAnErrorBody(int status) {
        assertThatThrownBy(() -> ElasticsearchHotelSearchIndex.call(() -> {
                    throw transport(status);
                }))
                .isInstanceOf(SearchUnavailableException.class);
        assertThatThrownBy(() -> ElasticsearchHotelSearchIndex.call(() -> {
                    throw elasticsearch(status);
                }))
                .isInstanceOf(SearchUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409})
    void requestErrorsAreNotAnOutage(int status) {
        ElasticsearchException rejected = elasticsearch(status);
        assertThatThrownBy(() -> ElasticsearchHotelSearchIndex.call(() -> {
                    throw rejected;
                }))
                .isSameAs(rejected);
        assertThatThrownBy(() -> ElasticsearchHotelSearchIndex.call(() -> {
                    throw transport(status);
                }))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(TransportException.class);
    }
}
