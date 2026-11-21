-- Story 2.2: payment rows get a closed status set and the double-charge guard.
-- A reservation has at most ONE active payment (PENDING = being charged, CAPTURED = paid). FAILED and REFUNDED rows
-- are history, so a declined card can be followed by another attempt. REFUNDING = claimed for a refund, provider
-- not yet confirmed (a provider error leaves it there, visibly, for a retry or reconciliation). Confirm inserts with
-- ON CONFLICT (reservation_id) WHERE status IN ('PENDING','CAPTURED') DO NOTHING against this index, so two concurrent
-- confirms share one payment (and one provider idempotency key) instead of charging twice.
ALTER TABLE payment
  ADD CONSTRAINT payment_status CHECK (status IN ('PENDING','CAPTURED','FAILED','REFUNDING','REFUNDED'));

CREATE UNIQUE INDEX payment_one_active ON payment (reservation_id) WHERE status IN ('PENDING','CAPTURED');
