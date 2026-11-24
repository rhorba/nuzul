package ma.nuzul.shared.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Inserts the outbox row with plain JDBC. MANDATORY: an append outside a business transaction is a bug. */
@Repository
class JdbcOutbox implements OutboxPort {

    static final String INSERT = """
            INSERT INTO outbox_event (id, aggregate, aggregate_id, type, payload)
            VALUES (:id, :aggregate, :aggregateId, :type, :payload::jsonb)
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcOutbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(Aggregate aggregate, UUID aggregateId, String type, Object payload) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT)
                .param("id", id)
                .param("aggregate", aggregate.column())
                .param("aggregateId", aggregateId)
                .param("type", type)
                .param("payload", json.writeValueAsString(payload))
                .update();
        return id;
    }
}
