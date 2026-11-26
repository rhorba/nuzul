package ma.nuzul.search.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Story 3.2: the reindex lock on real Postgres: exclusive while held, released on every path. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc // same cached context as the other MockMvc ITs
class JdbcReindexLockIT {

    private static final String HELD_BY_ANYONE = """
            SELECT count(*) FROM pg_locks
            WHERE locktype = 'advisory' AND classid = hashtext('nuzul.search')::oid
              AND objid = hashtext('reindex')::oid AND objsubid = 2
            """;

    @Autowired
    JdbcReindexLock lock;

    @Autowired
    JdbcTemplate jdbc;

    private int holders() {
        return jdbc.queryForObject(HELD_BY_ANYONE, Integer.class);
    }

    @Test
    void runsTheWorkWhileHoldingTheLockAndReleasesItAfter() {
        Optional<Integer> result = lock.runExclusively(this::holders);

        assertThat(result).contains(1);
        assertThat(holders()).isZero();
    }

    @Test
    void aSecondCallerWhileHeldGetsEmptyWithoutRunning() {
        var innerRan = new AtomicBoolean();

        Optional<Optional<Boolean>> outer =
                lock.runExclusively(() -> lock.runExclusively(() -> innerRan.getAndSet(true)));

        assertThat(outer).contains(Optional.empty());
        assertThat(innerRan).isFalse();
        assertThat(holders()).isZero();
    }

    @Test
    void theLockIsReleasedWhenTheWorkThrows() {
        var failure = new IllegalStateException("bulk rejected");

        assertThatThrownBy(() -> lock.runExclusively(() -> {
                    throw failure;
                }))
                .isSameAs(failure);

        assertThat(holders()).isZero();
        assertThat(lock.runExclusively(() -> "again")).contains("again");
    }
}
