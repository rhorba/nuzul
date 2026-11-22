-- Story 2.4: ShedLock's lock table, so only one replica runs the hold-expiry job at a time (system design §2.4).
-- The lock is an optimisation, not the safety net: expiry is one conditional UPDATE per reservation under
-- FOR UPDATE SKIP LOCKED, so two runs overlapping (lock_at_most_for elapsed, clock skew) still release each hold once.
-- Times come from the database clock (JdbcTemplateLockProvider usingDbTime), never from a replica's clock.
CREATE TABLE shedlock (
  name       VARCHAR(64)  PRIMARY KEY,
  lock_until TIMESTAMPTZ  NOT NULL,
  locked_at  TIMESTAMPTZ  NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);
