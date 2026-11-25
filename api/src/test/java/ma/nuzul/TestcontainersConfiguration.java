package ma.nuzul;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
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

    /** The idempotency cache (story 2.1). Same image as compose.yaml. */
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4.12-alpine")).withExposedPorts(6379);
    }

    /** The outbox relay's broker (story 3.1). Same image as compose.yaml (KRaft, single node). */
    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
    }

    /**
     * The hotel search index (story 3.2), security off as in compose. Major version 9 because Boot 4.1's
     * elasticsearch-java 9.4.8 client sends {@code compatible-with=9}, which 8.x servers refuse.
     *
     * <p>One container per test JVM, not per context: each cached Spring test context would otherwise start its own
     * (~800 MB), and the suite's dozen contexts exhaust Docker's memory. Boot closes container beans when a context
     * closes, so stop() is a no-op here and Ryuk removes the container when the JVM exits. start() on a running
     * container does nothing, so every context reuses it. Indices and the alias therefore outlive each test class: an
     * IT that needs a clean index wipes it itself, and an IT that needs the alias calls {@code ensureAliasExists()} in
     * its own setup (an earlier class may have deleted it after this context's startup bootstrap ran).
     */
    private static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(DockerImageName.parse("elasticsearch:9.4.8")
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch")) {
                @Override
                public void stop() {
                    // shared by every context of this JVM; Ryuk stops it
                }
            }.withEnv("xpack.security.enabled", "false").withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    @Bean
    @ServiceConnection
    ElasticsearchContainer elasticsearchContainer() {
        return ELASTICSEARCH;
    }
}
