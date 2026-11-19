package ma.nuzul.shared.time;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one source of "now" for business rules. Its zone ({@code app.time-zone}, Africa/Casablanca by default) decides
 * what "today" means, e.g. for the booking window. Tests replace it with a fixed {@code @Primary} Clock.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    @Bean
    Clock clock(@Value("${app.time-zone}") ZoneId zone) {
        return Clock.system(zone);
    }
}
