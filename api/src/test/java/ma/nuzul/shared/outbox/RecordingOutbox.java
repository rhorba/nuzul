package ma.nuzul.shared.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stands in for the outbox in unit tests (no transaction, no JSON): records every append in order. A test that makes
 * a transaction class throw before its append sees nothing recorded, which is what a rollback leaves in Postgres.
 */
public final class RecordingOutbox implements OutboxPort {

    public record Event(Aggregate aggregate, UUID aggregateId, String type, Object payload) {}

    public final List<Event> events = new ArrayList<>();

    @Override
    public UUID append(Aggregate aggregate, UUID aggregateId, String type, Object payload) {
        events.add(new Event(aggregate, aggregateId, type, payload));
        return UUID.randomUUID();
    }

    public List<String> types() {
        return events.stream().map(Event::type).toList();
    }
}
