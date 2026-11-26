package ma.nuzul.search.adapter.in.startup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import ma.nuzul.search.application.HotelSearchIndex;
import ma.nuzul.search.application.SearchProperties;
import org.junit.jupiter.api.Test;

/** Story 3.2: an unreachable Elasticsearch must never fail application startup. */
class SearchIndexBootstrapTest {

    private final HotelSearchIndex index = mock(HotelSearchIndex.class);

    @Test
    void ensuresTheAliasWhenEnabled() {
        new SearchIndexBootstrap(index, new SearchProperties("hotels", true)).ensureIndex();

        verify(index).ensureAliasExists();
    }

    @Test
    void swallowsAFailureSoStartupContinues() {
        doThrow(new UncheckedIOException(new ConnectException("Connection refused")))
                .when(index)
                .ensureAliasExists();
        var bootstrap = new SearchIndexBootstrap(index, new SearchProperties("hotels", true));

        assertThatCode(bootstrap::ensureIndex).doesNotThrowAnyException();
        verify(index).ensureAliasExists();
    }

    @Test
    void doesNothingWhenDisabled() {
        new SearchIndexBootstrap(index, new SearchProperties("hotels", false)).ensureIndex();

        verifyNoInteractions(index);
    }
}
