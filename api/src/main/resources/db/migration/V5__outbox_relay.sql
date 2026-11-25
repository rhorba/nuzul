-- Story 3.1: the outbox relay (ADR-4).
-- aggregate_id is the Kafka message key, so one aggregate's events land on one partition, in order.
-- attempts / last_error: a row that keeps failing for a non-retriable reason is parked at app.outbox.max-attempts
-- instead of blocking the rows behind it (outbox.failed gauge; reset attempts to 0 to replay it).
-- Nothing has written to outbox_event before this story, so the NOT NULL column needs no backfill.
ALTER TABLE outbox_event
  ADD COLUMN aggregate_id UUID NOT NULL,
  ADD COLUMN attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  ADD COLUMN last_error TEXT;
