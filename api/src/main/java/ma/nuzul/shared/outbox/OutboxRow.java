package ma.nuzul.shared.outbox;

import java.util.UUID;

/**
 * One unpublished outbox row as the relay reads it. {@code aggregate} stays the raw column value: a value no
 * {@link Aggregate} knows is a poison row for the relay to park, not a reason to fail the whole batch read.
 */
record OutboxRow(UUID id, String aggregate, UUID aggregateId, String type, String payload) {}
