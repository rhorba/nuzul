package ma.nuzul.search.application;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.search.*} (story 3.2).
 *
 * @param alias the name searches and live writes use; the concrete indices behind it are {@code <alias>-<timestamp>}
 * @param bootstrapEnabled create the index and alias at startup when they are missing
 */
@Validated
@ConfigurationProperties("app.search")
public record SearchProperties(
        @DefaultValue("hotels") @NotBlank String alias,
        @DefaultValue("true") boolean bootstrapEnabled) {}
