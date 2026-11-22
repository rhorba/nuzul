package ma.nuzul.reservation;

import static ma.nuzul.inventory.ReserveFixtures.night;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import ma.nuzul.TestcontainersConfiguration;
import ma.nuzul.inventory.ReserveFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Story 2.4 wiring: with {@code app.expiry.enabled} on, the scheduled job expires a due hold by itself and takes its
 * run through the ShedLock table (V4) under the {@code hold-expiry} lock.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.expiry.enabled=true", "app.expiry.interval=PT1S"})
@Timeout(90)
class HoldExpirySchedulingIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theScheduledJobExpiresADueHoldUnderTheShedLockLock() throws InterruptedException {
        var fx = new ReserveFixtures(jdbc);
        UUID roomTypeId = fx.roomType("owner-" + UUID.randomUUID());
        fx.inventory(roomTypeId, night(0), 5, 1);
        UUID id = fx.reservation(
                roomTypeId, "guest-" + UUID.randomUUID(), "HELD", Instant.now().minusSeconds(5));

        String status = "HELD";
        for (int i = 0; i < 60 && status.equals("HELD"); i++) {
            Thread.sleep(500);
            status = jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id);
        }

        assertThat(status).isEqualTo("EXPIRED");
        assertThat(fx.reserved(roomTypeId, night(0))).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM shedlock WHERE name = 'hold-expiry' AND locked_by <> ''", Integer.class))
                .isEqualTo(1);
    }
}
