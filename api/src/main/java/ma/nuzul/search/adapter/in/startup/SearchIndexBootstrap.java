package ma.nuzul.search.adapter.in.startup;

import ma.nuzul.search.application.HotelSearchIndex;
import ma.nuzul.search.application.SearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Driving adapter: once the app is ready, makes sure the hotel index and its alias exist ({@code
 * app.search.bootstrap-enabled}). Elasticsearch is never authoritative and search is eventually consistent, so an
 * unreachable cluster costs one WARN, not the startup: bookings must keep working, and the indexer retries its writes.
 */
@Component
class SearchIndexBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexBootstrap.class);

    private final HotelSearchIndex index;
    private final SearchProperties properties;

    SearchIndexBootstrap(HotelSearchIndex index, SearchProperties properties) {
        this.index = index;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ensureIndex() {
        if (!properties.bootstrapEnabled()) {
            return;
        }
        try {
            index.ensureAliasExists();
        } catch (RuntimeException e) {
            log.warn(
                    "Search alias '{}' could not be ensured at startup; search stays unavailable until it exists: {}",
                    properties.alias(),
                    e.toString());
        }
    }
}
