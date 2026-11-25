package ma.nuzul.search.adapter.out.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.time.Clock;
import ma.nuzul.search.application.HotelSearchIndex;
import ma.nuzul.search.application.SearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the hotel index on Boot's auto-configured {@link ElasticsearchClient} ({@code spring.elasticsearch.*}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
class ElasticsearchSearchConfig {

    @Bean
    HotelSearchIndex hotelSearchIndex(ElasticsearchClient client, SearchProperties properties) {
        return new ElasticsearchHotelSearchIndex(client, properties.alias(), Clock.systemUTC());
    }
}
