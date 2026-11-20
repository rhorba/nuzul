package ma.nuzul.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ma.nuzul.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The guard must prune room_inventory to the stay's monthly partition(s) on both sides of the UPDATE (the locking CTE
 * and the updated table), instead of scanning every month of the room type. Plain EXPLAIN: nothing is executed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReservePlanIT {

    /* A partition name, not its _pkey index: room_inventory_pYYYYMMDD or room_inventory_default. */
    private static final Pattern PARTITION = Pattern.compile("room_inventory_(?:p\\d+|default)(?!\\w)");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void reserveTouchesOnlyTheStaysMonthlyPartition() {
        /* Three nights inside one month, two months ahead: one premade monthly partition holds them all. */
        LocalDate checkIn =
                LocalDate.now(ZoneId.of("Africa/Casablanca")).plusMonths(2).withDayOfMonth(10);
        String expected = jdbc.queryForObject(
                "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                        + " WHERE i.inhparent = 'public.room_inventory'::regclass"
                        + " AND pg_get_expr(c.relpartbound, c.oid) LIKE ?",
                String.class,
                "%FROM ('" + checkIn.withDayOfMonth(1) + "')%");

        String sql = JdbcInventoryRepository.RESERVE
                .replace(":roomTypeId", "'" + UUID.randomUUID() + "'::uuid")
                .replace(":checkIn", "'" + checkIn + "'::date")
                .replace(":checkOut", "'" + checkIn.plusDays(3) + "'::date")
                .replace(":rooms", "1");
        String plan = String.join("\n", jdbc.queryForList("EXPLAIN (COSTS OFF) " + sql, String.class));

        Set<String> partitions = new TreeSet<>();
        Matcher m = PARTITION.matcher(plan);
        while (m.find()) {
            partitions.add(m.group());
        }
        assertThat(partitions).as(plan).containsExactly(expected);
    }
}
