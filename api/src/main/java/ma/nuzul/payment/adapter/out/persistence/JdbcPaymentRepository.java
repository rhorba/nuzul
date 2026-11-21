package ma.nuzul.payment.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.payment.PaymentStatus;
import ma.nuzul.payment.application.PaymentRepositoryPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain JDBC on payment. Money travels as NUMERIC text, never a double. */
@Repository
class JdbcPaymentRepository implements PaymentRepositoryPort {

    /* The conflict target names the partial unique index payment_one_active (V2) by its columns and predicate. */
    static final String INSERT_PENDING = """
            INSERT INTO payment (id, reservation_id, status, amount_mad)
            VALUES (:id, :reservationId, 'PENDING', :amountMad::numeric)
            ON CONFLICT (reservation_id) WHERE status IN ('PENDING', 'CAPTURED') DO NOTHING
            """;

    static final String COLUMNS = "id, reservation_id, status, amount_mad, provider_ref";

    private final JdbcClient jdbc;

    JdbcPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertPendingIfNoneActive(UUID id, UUID reservationId, BigDecimal amountMad) {
        return jdbc.sql(INSERT_PENDING)
                        .param("id", id)
                        .param("reservationId", reservationId)
                        .param("amountMad", amountMad.toPlainString())
                        .update()
                == 1;
    }

    @Override
    public Optional<PaymentRow> findActive(UUID reservationId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM payment WHERE reservation_id = :reservationId AND status IN ('PENDING', 'CAPTURED')")
                .param("reservationId", reservationId)
                .query(JdbcPaymentRepository::map)
                .optional();
    }

    @Override
    public Optional<PaymentRow> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE id = :id")
                .param("id", id)
                .query(JdbcPaymentRepository::map)
                .optional();
    }

    @Override
    public boolean transition(UUID id, PaymentStatus from, PaymentStatus to, String providerRef) {
        return jdbc.sql("""
                        UPDATE payment SET status = :to, provider_ref = coalesce(:providerRef::text, provider_ref)
                        WHERE id = :id AND status = :from
                        """)
                        .param("id", id)
                        .param("from", from.name())
                        .param("to", to.name())
                        .param("providerRef", providerRef)
                        .update()
                == 1;
    }

    private static PaymentRow map(ResultSet rs, int row) throws SQLException {
        return new PaymentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("reservation_id", UUID.class),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("amount_mad"),
                rs.getString("provider_ref"));
    }
}
