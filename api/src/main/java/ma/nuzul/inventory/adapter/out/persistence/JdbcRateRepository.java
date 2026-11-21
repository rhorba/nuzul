package ma.nuzul.inventory.adapter.out.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ma.nuzul.inventory.application.RateRepositoryPort;
import ma.nuzul.inventory.domain.RateChange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain JDBC on rate (no JPA entity, like room_inventory). Never writes {@code free_cancel_hours}. */
@Repository
class JdbcRateRepository implements RateRepositoryPort {

    /*
     * One statement per batch, nights locked in ascending order like room_inventory. No guard: a rate can always be
     * overwritten. free_cancel_hours is absent from both the column list (insert takes DEFAULT 48) and the SET list
     * (update keeps it). Prices travel as decimal text and are cast to numeric in Postgres: exact, never a double.
     */
    static final String UPSERT_RATES = """
            INSERT INTO rate (room_type_id, night, price_mad, refundable)
            SELECT :roomTypeId, x.n, x.p, x.r
            FROM unnest(:nights::date[], :prices::numeric[], :refundables::boolean[]) AS x(n, p, r)
            ORDER BY x.n
            ON CONFLICT (room_type_id, night) DO UPDATE
            SET price_mad = EXCLUDED.price_mad, refundable = EXCLUDED.refundable
            """;

    private final JdbcClient jdbc;

    JdbcRateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsertRates(UUID roomTypeId, List<RateChange> changes) {
        String[] nights = changes.stream().map(c -> c.night().toString()).toArray(String[]::new);
        String[] prices =
                changes.stream().map(c -> c.priceMad().toPlainString()).toArray(String[]::new);
        Boolean[] refundables = changes.stream().map(RateChange::refundable).toArray(Boolean[]::new);
        jdbc.sql(UPSERT_RATES)
                .param("roomTypeId", roomTypeId)
                .param("nights", nights)
                .param("prices", prices)
                .param("refundables", refundables)
                .update();
    }

    @Override
    public PricedNights priceNights(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut) {
        return jdbc.sql("""
                        SELECT count(*) AS nights, coalesce(sum(price_mad), 0.00) AS total,
                               coalesce(bool_and(refundable), false) AS all_refundable,
                               coalesce(max(free_cancel_hours), 0) AS max_hours
                        FROM rate
                        WHERE room_type_id = :roomTypeId AND night >= :checkIn AND night < :checkOut
                        """)
                .param("roomTypeId", roomTypeId)
                .param("checkIn", checkIn)
                .param("checkOut", checkOut)
                .query((rs, i) -> new PricedNights(
                        rs.getInt("nights"),
                        rs.getBigDecimal("total"),
                        rs.getBoolean("all_refundable"),
                        rs.getInt("max_hours")))
                .single();
    }
}
