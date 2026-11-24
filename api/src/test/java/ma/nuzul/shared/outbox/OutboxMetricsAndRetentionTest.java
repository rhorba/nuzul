package ma.nuzul.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** Story 3.1: the gauges' fail-soft reads and the scheduled jobs' wiring; the SQL is OutboxRelayIT. */
class OutboxMetricsAndRetentionTest {

    /** Answers the gauge and retention reads with fixed values, or fails them like a database outage. */
    private static final class StubStore implements OutboxRelayStore {

        boolean down;
        double lag = 12.5;
        long parked = 2;
        int deleted;
        Duration retention;
        int maxAttempts;

        @Override
        public List<OutboxRow> lockBatch(int maxAttempts, int batchSize) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public void markPublished(UUID id) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public int markFailed(UUID id, String error) {
            throw new UnsupportedOperationException("not used here");
        }

        @Override
        public double lagSeconds(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            if (down) {
                throw new DataAccessResourceFailureException("connection refused");
            }
            return lag;
        }

        @Override
        public long parkedCount(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            if (down) {
                throw new DataAccessResourceFailureException("connection refused");
            }
            return parked;
        }

        @Override
        public int deletePublishedBefore(Duration retention) {
            this.retention = retention;
            return deleted;
        }
    }

    private final StubStore store = new StubStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /** Held in a field: a gauge keeps only a weak reference (in the app, the Spring singleton holds it). */
    private final OutboxMetrics metrics = new OutboxMetrics(store, 10);

    {
        metrics.bindTo(registry);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void gaugesReadTheStoreOnEachScrape() {
        assertThat(gauge(OutboxMetrics.LAG)).isEqualTo(12.5);
        assertThat(gauge(OutboxMetrics.FAILED)).isEqualTo(2.0);
        assertThat(store.maxAttempts).isEqualTo(10);

        store.lag = 0;
        store.parked = 0;

        assertThat(gauge(OutboxMetrics.LAG)).isZero();
        assertThat(gauge(OutboxMetrics.FAILED)).isZero();
    }

    @Test
    void aDatabaseFailureReportsNaNInsteadOfThrowing() {
        store.down = true;

        assertThat(gauge(OutboxMetrics.LAG)).isNaN();
        assertThat(gauge(OutboxMetrics.FAILED)).isNaN();
    }

    @Test
    void retentionDeletesWithTheConfiguredDuration() {
        store.deleted = 3;
        OutboxRetentionJob job = new OutboxRetentionJob(store, Duration.ofDays(7));

        assertThat(job.deleteExpired()).isEqualTo(3);
        assertThat(store.retention).isEqualTo(Duration.ofDays(7));

        store.deleted = 0;
        job.run();
        assertThat(store.retention).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void negativeRetentionIsRejected() {
        assertThatThrownBy(() -> new OutboxRetentionJob(store, Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.outbox.retention");
    }

    @Test
    void relayJobRunsOneRelay() {
        OutboxRelay relay = mock(OutboxRelay.class);

        new OutboxRelayJob(relay).run();

        verify(relay).relay();
    }
}
