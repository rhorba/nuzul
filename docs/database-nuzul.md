# Database Design: Nuzul

Owner: DBA · Status: DRAFT

## 1. Database selection
PostgreSQL 16 is the source of truth: transactions, row locks and CHECK constraints carry the no-oversell guarantee.
Elasticsearch holds a rebuildable projection and Redis holds disposable data. Neither one is ever authoritative.

## 2. Schema (Flyway V1)
```sql
CREATE TABLE hotel (
  id UUID PRIMARY KEY, owner_sub TEXT NOT NULL, name TEXT NOT NULL, city TEXT NOT NULL,
  address TEXT, stars SMALLINT CHECK (stars BETWEEN 1 AND 5), amenities TEXT[] NOT NULL DEFAULT '{}',
  lat NUMERIC(9,6), lon NUMERIC(9,6), status TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), version INT NOT NULL DEFAULT 0);

CREATE TABLE room_type (
  id UUID PRIMARY KEY, hotel_id UUID NOT NULL REFERENCES hotel(id),
  name TEXT NOT NULL, capacity SMALLINT NOT NULL CHECK (capacity > 0), bed_config TEXT);

CREATE TABLE room_inventory (
  room_type_id UUID NOT NULL REFERENCES room_type(id), night DATE NOT NULL,
  total INT NOT NULL CHECK (total >= 0), reserved INT NOT NULL DEFAULT 0,
  PRIMARY KEY (room_type_id, night),
  CONSTRAINT inventory_no_oversell CHECK (reserved >= 0 AND reserved <= total)
) PARTITION BY RANGE (night);          -- monthly partitions managed by pg_partman (see §2.1)

CREATE TABLE rate (
  room_type_id UUID NOT NULL REFERENCES room_type(id), night DATE NOT NULL,
  price_mad NUMERIC(10,2) NOT NULL CHECK (price_mad > 0), refundable BOOLEAN NOT NULL DEFAULT true,
  free_cancel_hours INT NOT NULL DEFAULT 48, PRIMARY KEY (room_type_id, night));

CREATE TABLE reservation (
  id UUID PRIMARY KEY, guest_sub TEXT NOT NULL, hotel_id UUID NOT NULL REFERENCES hotel(id),
  room_type_id UUID NOT NULL REFERENCES room_type(id),
  check_in DATE NOT NULL, check_out DATE NOT NULL, rooms SMALLINT NOT NULL CHECK (rooms > 0),
  status TEXT NOT NULL CHECK (status IN ('HELD','CONFIRMED','CANCELLED','EXPIRED')),
  total_mad NUMERIC(12,2) NOT NULL, hold_expires_at TIMESTAMPTZ, idempotency_key UUID NOT NULL,
  guest_name TEXT NOT NULL, guest_email TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), version INT NOT NULL DEFAULT 0,
  CONSTRAINT reservation_half_open_dates CHECK (check_out > check_in),
  CONSTRAINT reservation_idem UNIQUE (guest_sub, idempotency_key));
CREATE INDEX reservation_expiry ON reservation (hold_expires_at) WHERE status = 'HELD';
CREATE INDEX reservation_guest ON reservation (guest_sub, created_at DESC);

CREATE TABLE payment (id UUID PRIMARY KEY, reservation_id UUID NOT NULL REFERENCES reservation(id),
  provider_ref TEXT, status TEXT NOT NULL, amount_mad NUMERIC(12,2) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
-- V2 (story 2.2): closed status set + at most one active payment per reservation (the double-charge guard).
ALTER TABLE payment ADD CONSTRAINT payment_status CHECK (status IN ('PENDING','CAPTURED','FAILED','REFUNDING','REFUNDED'));
CREATE UNIQUE INDEX payment_one_active ON payment (reservation_id) WHERE status IN ('PENDING','CAPTURED');
-- V3 (story 2.3): the cancellation policy, frozen at hold time like total_mad (backfilled from rates).
ALTER TABLE reservation ADD COLUMN refundable BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN free_cancel_until TIMESTAMPTZ;  -- NULL = never free
-- V4 (story 2.4): ShedLock's lock table (one replica runs hold expiry; times from the DB clock).
CREATE TABLE shedlock (name VARCHAR(64) PRIMARY KEY, lock_until TIMESTAMPTZ NOT NULL,
  locked_at TIMESTAMPTZ NOT NULL, locked_by VARCHAR(255) NOT NULL);

CREATE TABLE outbox_event (id UUID PRIMARY KEY, aggregate TEXT NOT NULL, type TEXT NOT NULL,
  payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), published_at TIMESTAMPTZ);
CREATE INDEX outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_event (consumer TEXT, event_id UUID, PRIMARY KEY (consumer, event_id));
```

### 2.1 Partitioning (pg_partman)
- `V1__schema.sql` installs `pg_partman` in schema `partman` and calls
  `partman.create_parent('public.room_inventory', 'night', '1 month', p_premake => 18)`.
  This premakes 18 months on either side of the current month, plus `room_inventory_default`, which catches
  any night outside the window so an insert never fails for a missing month.
- The `pg_partman_bgw` background worker (`shared_preload_libraries`, hourly, db and role `nuzul`) runs
  `partman.run_maintenance_proc()` to premake future months. No application-side job is needed.
- Needs the custom image `deploy/postgres/Dockerfile` (Postgres 16 bookworm + `postgresql-16-partman`). It's used
  by compose and Testcontainers, and pushed to GHCR as `nuzul-postgres`. The bgw settings live in the image's
  `postgresql.conf.sample`, so no `-c` flags are needed.
- **DEFAULT partition guard**: a row in `room_inventory_default` for a future month blocks pg_partman from creating
  that month, and maintenance then fails every hour. So the API refuses inventory and rate nights beyond the
  180-day booking window (story 1.3), which keeps every real night inside the premade months.
- **Privileges**: `pg_partman` is not a trusted extension. Compose and Testcontainers run Flyway as superuser.
  On CloudNativePG, create `partman` and the extension in `bootstrap.initdb.postInitApplicationSQL`; V1 uses
  `IF NOT EXISTS`, so it then skips them. The CNPG cluster needs a pg_partman-enabled operand image.
- Retention of past partitions is not configured yet (YAGNI). Add `part_config.retention` when archiving is needed.

## 3. Rules that must not be undone
1. `inventory_no_oversell` is the last line of defence. Never drop it to "fix" a failing request.
2. Night ranges are **half-open** `[check_in, check_out)`. Checkout day is not a night stayed.
3. Money is `NUMERIC`, never `float`. Totals are computed in SQL from `rate` rows at hold time and frozen on the reservation.
4. Hold expiry decrements inventory in the **same transaction** as the status change.
5. Every writer of `room_inventory` (bulk upsert, reserve, expiry/cancel release) locks rows in **ascending `(room_type_id, night)` order**: nights ascending within a room type, and a transaction that touches several room types takes them in `room_type_id` order. Concurrent writers over overlapping rows then wait for each other and cannot deadlock.
6. `total` is in every reserve's SET list, written back unchanged (system design, guard section). Any future trigger or CDC filter on capacity changes must use `WHEN (OLD.total IS DISTINCT FROM NEW.total)` or compare before/after values, never `UPDATE OF total`.

## 4. Integrity tests (Testcontainers)
They assert that the DB refuses bad writes: oversell, a negative reserved count, `check_out <= check_in`, a duplicate idempotency key, an unknown status.

## 5. Elasticsearch index `hotels`
`{id, name(text+keyword), city(keyword), location(geo_point), stars, amenities(keyword[]), minPriceByMonth(object)}`.
It is rebuilt from Postgres by `POST /admin/search/reindex` (platform admin).
