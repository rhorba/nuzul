package ma.nuzul;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the same Postgres image as compose (deploy/postgres: Postgres 16 + pg_partman, bgw configured in the
 * image's postgresql.conf), so the V1 migration behaves in tests exactly as it does locally.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Must match pg_partman_bgw.dbname / role baked into the image. */
    static final String DB = "nuzul";

    /** Works whether tests run from api/ (Maven, CI) or the repo root (some IDE run configs). */
    static Path postgresDockerfile() {
        return Stream.of(Path.of("..", "deploy", "postgres", "Dockerfile"), Path.of("deploy", "postgres", "Dockerfile"))
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("deploy/postgres/Dockerfile not found from "
                        + Path.of("").toAbsolutePath()));
    }

    /** Built once per machine and cached by Docker (deleteOnExit=false). */
    static DockerImageName nuzulPostgresImage() {
        String image = new ImageFromDockerfile("nuzul-postgres:test", false)
                .withDockerfile(postgresDockerfile())
                .get();
        return DockerImageName.parse(image).asCompatibleSubstituteFor("postgres");
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(nuzulPostgresImage())
                .withDatabaseName(DB)
                .withUsername(DB);
    }
}
