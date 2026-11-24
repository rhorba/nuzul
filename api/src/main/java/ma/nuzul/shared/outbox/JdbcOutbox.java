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

    /**
     * clock_timestamp(), not the column default now() (the transaction's start): a transaction that waited on the
     * aggregate's row lock must sort after the event it waited for, and the relay publishes in created_at order.
     */
    static final String INSERT = """
            INSERT INTO outbox_event (id, aggregate, aggregate_id, type, payload, created_at)
            VALUES (:id, :aggregate, :aggregateId, :type, :payload::jsonb, clock_timestamp())
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
