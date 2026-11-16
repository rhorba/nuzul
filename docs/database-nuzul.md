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
) PARTITION BY RANGE (night);          -- monthly partitions created by a Flyway Java migration + job

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
  CHECK (check_out > check_in),
  CONSTRAINT reservation_idem UNIQUE (guest_sub, idempotency_key));
CREATE INDEX reservation_expiry ON reservation (hold_expires_at) WHERE status = 'HELD';
CREATE INDEX reservation_guest ON reservation (guest_sub, created_at DESC);

CREATE TABLE payment (id UUID PRIMARY KEY, reservation_id UUID NOT NULL REFERENCES reservation(id),
  provider_ref TEXT, status TEXT NOT NULL, amount_mad NUMERIC(12,2) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE outbox_event (id UUID PRIMARY KEY, aggregate TEXT NOT NULL, type TEXT NOT NULL,
  payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), published_at TIMESTAMPTZ);
CREATE INDEX outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_event (consumer TEXT, event_id UUID, PRIMARY KEY (consumer, event_id));
```

## 3. Rules that must not be undone
1. `inventory_no_oversell` is the last line of defence. Never drop it to "fix" a failing request.
2. Night ranges are **half-open** `[check_in, check_out)`. Checkout day is not a night stayed.
3. Money is `NUMERIC`, never `float`. Totals are computed in SQL from `rate` rows at hold time and frozen on the reservation.
4. Hold expiry decrements inventory in the **same transaction** as the status change.

## 4. Integrity tests (Testcontainers)
They assert that the DB refuses bad writes: oversell, a negative reserved count, `check_out <= check_in`, a duplicate idempotency key, an unknown status.

## 5. Elasticsearch index `hotels`
`{id, name(text+keyword), city(keyword), location(geo_point), stars, amenities(keyword[]), minPriceByMonth(object)}`.
It is rebuilt from Postgres by `POST /admin/search/reindex` (platform admin).
