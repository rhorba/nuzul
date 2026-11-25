# System Design: Nuzul (نزول) — hotel reservation platform

> Structured with the five-step framework from Hayk Simonyan's *Design Software Systems Like a
> Senior Engineer* (Booking.com section, 0:50:52–1:50:45): clarify requirements → data and API →
> high-level architecture → scale and reliability → trade-offs and extensions.
> Owner: System Designer · Status: DRAFT, waiting for owner approval (CTS rule 13)

---

## Step 1 — Clarify requirements

### Functional (v1)
1. **Search**: hotels by city, check-in and check-out dates, guests and rooms. Filters for price, stars and amenities.
2. **Hotel page**: room types, with price and availability for the chosen dates.
3. **Reserve**: create a **hold** on N rooms of one room type for a date range. The hold expires after 10 minutes.
4. **Confirm**: pay for the hold, which turns it into a confirmed reservation. Payment goes through a `PaymentPort`; v1 ships a fake adapter, and Stripe test mode is a later extension.
5. **Cancel** a confirmed reservation, subject to the free-cancellation deadline set on the rate.
6. **My reservations** for a guest.
7. **Hotel admin**: CRUD for hotels and room types, bulk edits of inventory and nightly rates, photo upload.
8. **Notifications**: a confirmation or cancellation email, sent asynchronously.

### Out of scope (YAGNI)
Reviews, loyalty, multi-currency (MAD only), real payments, overbooking policies, channel managers, mobile app.

### Non-functional targets
| Concern | Target | Why |
|---|---|---|
| **Correctness** | **Zero double bookings**, i.e. never reserve more rooms than inventory | The one property a booking system cannot get wrong |
| Consistency | Strong for inventory and reservations; eventual (≤ 5 s) for search | Search can show stale results; booking cannot |
| Idempotency | A retried `POST /reservations` with the same key returns the same result | Mobile networks and double-clicks |
| Latency | Search p95 < 300 ms · hotel page p95 < 250 ms · reserve p95 < 500 ms | |
| Availability | 99.9 % on the read path; the booking path may refuse rather than oversell | CP for writes, AP for reads |
| Read:write | ~100 : 1 | People search far more than they book |
| Privacy | Guest PII under Morocco's Loi 09-08 and GDPR principles | Names, emails, phone numbers |

### Capacity math (designed for; the demo runs far smaller)
- 50 k hotels × 5 room types × 365 nights = **~91 M inventory rows per year**. Partition `room_inventory` by month.
- 100 k reservations/day ≈ 1.2 writes/s on average, **~15/s at peak** (10×, plus flash sales). Postgres handles this easily; the risk is **contention on hot rows**, not throughput.
- 10 M searches/day ≈ 115 rps on average, **~1 200 rps at peak**. Served by Elasticsearch plus a Redis cache, never by the inventory table.
- Photos: 50 k hotels × 30 photos × 300 KB ≈ **450 GB**. Store on S3 and serve through CloudFront.

---

## Step 2 — Data and API layer

### Core entities
```
Hotel(id, name, city, address, stars, amenities[], geo, status)
RoomType(id, hotel_id, name, capacity, bed_config)
RoomInventory(room_type_id, night DATE, total INT, reserved INT,
              PK(room_type_id, night), CHECK (reserved BETWEEN 0 AND total))
Rate(room_type_id, night, price_mad NUMERIC, refundable BOOL, free_cancel_hours INT)
Reservation(id, guest_sub, hotel_id, room_type_id, check_in, check_out, rooms,
            status HELD|CONFIRMED|CANCELLED|EXPIRED, total_mad, hold_expires_at,
            idempotency_key UNIQUE(guest_sub, idempotency_key), version)
Payment(id, reservation_id, provider_ref, status, amount_mad)
OutboxEvent(id, aggregate, aggregate_id, type, payload JSONB, created_at, published_at, attempts, last_error)
```
**Inventory is counted per night, per room type**, the way Booking.com does it, not per physical room. Physical room assignment happens at the front desk and is out of scope.

### Inventory logic: the double-booking guard (the core of the interview)
Reserving `n` rooms for `[check_in, check_out)` runs inside one transaction, as one statement:
```sql
WITH locked AS (
  SELECT night, reserved, total FROM room_inventory
   WHERE room_type_id = :rt AND night >= :check_in AND night < :check_out
   ORDER BY night
   FOR NO KEY UPDATE)
UPDATE room_inventory r
   SET reserved = l.reserved + :n, total = l.total
  FROM locked l
 WHERE r.room_type_id = :rt AND r.night >= :check_in AND r.night < :check_out
   AND r.night = l.night
   AND l.reserved + :n <= l.total;
-- rows updated MUST equal the number of nights, otherwise ROLLBACK → 409 SOLD_OUT
-- (a night with no room_inventory row is not updated, so it counts as sold out)
```
- **One atomic conditional update, decided on the locked values.** The CTE takes the row locks; a lock that had to wait returns the latest committed version (including this transaction's own earlier writes). The guard `l.reserved + n <= l.total` and every non-key column of the new row come from those locked values, and the lock is held from the CTE through the UPDATE (and to commit), so nothing can change them in between. Two concurrent requests for the last room cannot both succeed, and a room released (or a total raised) while we waited is sold. No read-then-write gap.
- **`r` only locates the row**, through the immutable key columns `(room_type_id, night)`.
- **Why every non-key column, `total` included.** Under READ COMMITTED the target row is the version the statement snapshot saw. Postgres builds a provisional new row from that snapshot tuple plus the SET list and evaluates the CHECK constraint on it *before* it detects the concurrent update and recomputes the row on the latest version. The provisional row must therefore already carry the real values. `total` is written back with the value held under our own lock: it is never changed.
- **How we got here (reproduced on PG16, `ReserveLockWaitIT`).** A guard on `r` (`r.reserved + n <= r.total`) refused a room released while the request waited: a false SOLD_OUT. Moving the guard to `l` but computing `r.reserved + n` failed the CHECK (23514) on the same release, and `l.reserved + n` with `total` left to come from `r` failed it again after a concurrent total raise. Each provisional row carried a stale value from the snapshot tuple.
- **The `CHECK (reserved <= total)` constraint is the backstop.** Even a buggy code path cannot oversell; a violation (23514) is never caught or translated.
- **Deadlock avoidance: ordered locks.** The CTE locks the stay's nights in ascending date order (`ORDER BY night FOR NO KEY UPDATE`), the same order the bulk inventory upsert uses, so overlapping writers queue instead of deadlocking. Every writer of `room_inventory` follows this rule (database doc §3 rule 5). `FOR NO KEY UPDATE` because the key never changes. `ReserveDeadlockFreedomIT` is the regression guard: it stores the nights in reverse heap order and turns index and bitmap scans off in the reserve transactions, so nothing locks in night order by accident, and sets `deadlock_timeout` to 10 ms so a deadlock is reported at once. With the CTE it sees no deadlock; swapping in a plain range UPDATE (mutation check) fails its "no 40P01" assertion within seconds.
- **Partition pruning.** The night range is repeated on `r`, so both sides of the statement touch only the stay's monthly partitions (`ReservePlanIT`).
- **Still one round trip.** The lock and the guarded write are a single statement sent once; this is not option (b) below, which reads the locked rows back into the application and then sends a second statement.
- **Never changes `total`.** Implemented by `InventoryReservations.reserve()` (inventory module API, propagation MANDATORY: it joins the hold transaction and never opens its own). A 40P01 retry, if any, wraps the caller's whole transaction (story 2.1), never `reserve()` itself.
- Rejected options: (a) optimistic locking with `@Version` retries, which thrash under flash-sale contention; (b) `SELECT … FOR UPDATE` then update, which works but needs two round trips; (c) a distributed Redis lock, which adds a second source of truth and fails open on Redis failover.

### Holds and expiry
A `HELD` reservation has already incremented `reserved`. A scheduled job (ShedLock, every 30 s) finds `HELD` rows past `hold_expires_at`, sets them to `EXPIRED` and decrements inventory in the same transaction. **Redis TTLs are never the source of truth** for releasing inventory.

**As built (story 2.4):** `HoldExpiryJob` (`@Scheduled` fixed delay `app.expiry.interval`, `@SchedulerLock("hold-expiry")`,
ShedLock JDBC on the `shedlock` table with the **database clock**) calls `ExpireHoldsUseCase`:
1. One indexed read (`reservation_expiry`): ids of `HELD` rows with `hold_expires_at <= now`, oldest first, at most
   `app.expiry.batch-size` (500). The cap bounds one run, not a transaction; a backlog drains over the next runs.
2. **One short transaction per reservation**: `SELECT … FOR UPDATE SKIP LOCKED` (re-checking `HELD` and due), the
   conditional `HELD → EXPIRED`, then `InventoryReservations.release`. A row a confirm or cancel holds is skipped, not
   waited on; that writer decides and the next run looks again. Reservation row first, inventory rows second, as in
   cancel, so they cannot deadlock. One failing reservation is logged and skipped.

ShedLock only saves duplicate work: two runs overlapping (lock lapsed, two replicas) still expire and release each hold
once (release gate 4, `HoldExpiryIT`). A hold ending exactly at `now` is over (`hold_expires_at > now` is confirm's
window), and `hold_expires_at` is kept on the expired row. `app.expiry.enabled=false` turns the job off.

### Idempotency
`POST /reservations` requires an `Idempotency-Key` header (a UUID). Keys are scoped per guest.
1. Redis `GET idem:{guest}:{key}`. A hit replays the stored response (201, identical body).
2. Postgres: a known `(guest_sub, idempotency_key)` row is replayed the same way.
3. Otherwise the hold transaction inserts the reservation **before** touching inventory, with
   `INSERT … ON CONFLICT ON CONSTRAINT reservation_idem DO NOTHING`. A concurrent duplicate waits on the unique index
   until the first transaction ends, then reads and replays the committed row (or goes ahead if it rolled back). It
   never touches inventory, so 5 parallel identical requests give 1 reservation and 5 identical 201s.
4. After commit, the response is stored in Redis for 24 h (`SET … EX 86400`).
5. The same key with a different body (room type, dates, rooms) is `422 IDEMPOTENCY_KEY_REUSED`.

Redis is only a cache. Every Redis error counts as a miss (timeouts 300 ms), and `UNIQUE(guest_sub, idempotency_key)`
is the guarantee that survives a Redis flush or outage. *(Changed in story 2.1: the earlier `SET … PENDING NX` /
`409 IN_PROGRESS` step contradicted release gate 3, which requires identical responses to parallel duplicates.)*

### Confirm and payment (story 2.2)
No provider call ever runs inside a database transaction.
1. Load the reservation **for this guest** (another guest's id is `404`). `CONFIRMED` → `200` again (retry-safe);
   `CANCELLED` → `409`; `EXPIRED` or past `hold_expires_at` → `410 HOLD_EXPIRED`.
2. **tx 1**: `Payments.begin` returns the reservation's active payment, inserting a `PENDING` one if there is none.
   The partial unique index `payment_one_active` (V2) allows one `PENDING|CAPTURED` payment per reservation, so
   concurrent confirms share one payment.
3. **No transaction**: charge through `PaymentPort`, with the payment id as the provider idempotency key. Two confirms
   sharing the payment are charged once. A decline marks it `FAILED` → `402 PAYMENT_FAILED`; the hold stays `HELD`, so
   another card can be tried.
4. **tx 2**: `UPDATE reservation SET status='CONFIRMED' WHERE id=? AND status='HELD' AND hold_expires_at > now`, and
   capture the payment in the same commit. A confirm and the expiry job (2.4) can never both win.
5. If tx 2 confirmed nothing, refund. The refund first **claims** the payment (`PENDING → REFUNDING`, conditional),
   then calls the provider, then marks it `REFUNDED` (a provider error leaves `REFUNDING`, retried, never a false
   `REFUNDED`). Capture is the same kind of conditional update on the same row, so exactly one of them
   wins. If a concurrent confirm captured first, nothing is refunded and the answer is `200`; if the refund claimed
   first, that confirm's tx 2 rolls back. Expired → `410`, cancelled meanwhile → `409`.

`hold_expires_at` is never cleared, because a hold replay (idempotency) still answers with it.
**Known gap:** a crash between an approved charge and tx 2 leaves the payment `PENDING`. The next confirm resumes it
with the same idempotency key; if the hold expires first, a reconciliation job (with the Stripe adapter) must refund it.

### Cancel (story 2.3)
The cancellation policy is **frozen at hold time** on the reservation (`refundable`, `free_cancel_until`, V3), like
`total_mad`: refundable only if every night of the stay is, free until the check-in day 00:00 (hotel time zone) minus the
strictest `free_cancel_hours`. A later rate edit never changes a guest's terms.
1. Load **for this guest** (else `404`). `CANCELLED` → `200` again (finishing a pending refund); `EXPIRED` → `410`.
2. **One transaction**: conditional `HELD|CONFIRMED → CANCELLED` from the status just read (cancel, confirm and expiry
   never both win), `InventoryReservations.release` (ordered `FOR NO KEY UPDATE`, every column from the locked values,
   the exact inverse of reserve), and for a `CONFIRMED` stay still inside its free window, `Payments.claimRefund`
   (`CAPTURED → REFUNDING`).
3. **After commit**: the provider refund (`REFUNDING → REFUNDED`). A provider error leaves `REFUNDING`; the answer says
   `refund: PENDING` and the next cancel call sends it again.

Answer: `200 {id, status: CANCELLED, refund: NONE|PENDING|COMPLETED, refundMad}`. A `HELD` hold can be cancelled too
(its rooms go back on sale at once; nothing was paid).

### API contracts (REST, `/api/v1`, OpenAPI 3 generated by springdoc)
| Method | Path | Notes |
|---|---|---|
| GET | `/search?city&checkIn&checkOut&guests&rooms&minPrice&maxPrice&stars&page` | Elasticsearch, then a Postgres availability check on the page of results |
| GET | `/hotels/{id}?checkIn&checkOut&rooms` | Room types with `available` and `totalPrice`; cached 30 s |
| POST | `/reservations` | `Idempotency-Key` header → `201 {id, status: HELD, holdExpiresAt, totalMad}` · `409 SOLD_OUT` |
| POST | `/reservations/{id}/confirm` | `{paymentToken}` → `200 CONFIRMED` · `410 HOLD_EXPIRED` · `402 PAYMENT_FAILED` |
| POST | `/reservations/{id}/cancel` | → `200 {status: CANCELLED, refund: NONE\|PENDING\|COMPLETED, refundMad}` (policy frozen at hold) · `410 HOLD_EXPIRED` · `404` |
| GET | `/me/reservations` | Paginated |
| POST/PUT | `/admin/hotels`, `/admin/hotels/{id}/room-types` | Role `HOTEL_ADMIN`, scoped to the admin's own hotels |
| PUT | `/admin/room-types/{id}/inventory` | Bulk `[{night, total}]`; refused if `total < reserved` |
| PUT | `/admin/room-types/{id}/rates` | Bulk `[{night, priceMad, refundable}]` |
| POST | `/admin/hotels/{id}/photos/presign` | Returns an S3 presigned PUT URL |

Errors use RFC 9457 `application/problem+json` with a stable `code`.

### Storage choices
| Store | Holds | Why |
|---|---|---|
| **PostgreSQL 16** | Hotels, inventory, rates, reservations, payments, outbox | ACID, row locks and CHECK constraints: the guard above needs them |
| **Elasticsearch 9** | A `hotels` search projection (city, geo, stars, amenities, min price per month) | Full-text, filters and geo at 1 k rps; Postgres stays free for writes |
| **Redis 7** | Idempotency keys, hotel-page cache, rate limiting (Bucket4j) | Low latency, TTLs |
| **Kafka** | `reservation-events`, `catalog-events` | Decouples search indexing and email from the booking transaction |
| **S3 + CloudFront** | Hotel photos | Object storage and CDN; the app never proxies bytes |

---

## Step 3 — High-level architecture

```
 Browser (Angular SPA)
     │  HTTPS
     ▼
 Traefik ingress (k3s) ──► Keycloak (OIDC: guest + hotel-admin realms roles)
     │
     ▼
 nuzul-api  (Spring Boot 4, modular monolith, Spring Modulith boundaries)
  ├─ catalog       hotels, room types, photos (S3 presign)
  ├─ inventory     room_inventory, rates, the conditional UPDATE
  ├─ reservation   hold / confirm / cancel / expiry job, idempotency
  ├─ payment       PaymentPort → FakePaymentAdapter (Stripe adapter later)
  ├─ search        ES queries + availability merge
  └─ outbox        poller → Kafka
     │           │            │             │
  Postgres     Redis    Elasticsearch     Kafka ──► search-indexer (same app, consumer group)
                                              └──► notifier → SMTP (Mailpit in dev)
 Photos: Angular ──presigned PUT──► S3 ◄── CloudFront ◄── browser GET
```

### User journey: booking the last room
1. A guest searches "Marrakech, 27–30 Dec, 2 guests". ES returns 20 hotels; one batched SQL query checks inventory for those 20 and drops the sold-out ones.
2. The guest opens a hotel page (Redis cache hit, or Postgres) and picks "Double, 1 room".
3. The Angular app generates an `Idempotency-Key` and calls `POST /reservations`. The conditional UPDATE succeeds for 3 of 3 nights. In one transaction it inserts the reservation (`HELD`, expires in 10 min) and an outbox row.
4. The guest pays. `confirm` calls `PaymentPort`; on success the reservation becomes `CONFIRMED` and an outbox row `ReservationConfirmed` is written.
5. The outbox poller publishes to Kafka. The notifier sends the email and the indexer refreshes the min-price projection.
6. A second guest racing for the same room gets `409 SOLD_OUT` at step 3, never later.

---

## Step 4 — Scale and reliability

### Bottlenecks and how they are handled
| Bottleneck | Mitigation |
|---|---|
| Hot inventory rows (a popular riad on New Year's Eve) | A short transaction (one UPDATE plus inserts), no external calls inside it, and payment happens **outside** the hold transaction. Measured with a 200-thread concurrency test and a k6 spike test. |
| Search traffic | ES with 1 replica, a Redis cache on hotel pages, and an availability check limited to the current result page |
| Inventory table growth | Monthly partitions on `night`; past partitions archived |
| Expiry job on several pods | ShedLock, so only one pod runs it; up to 500 holds per run, one `FOR UPDATE SKIP LOCKED` transaction each |

### Single points of failure
| Component down | Behaviour |
|---|---|
| Elasticsearch | Search falls back to a degraded Postgres query (city plus dates, no full-text) behind a circuit breaker (Resilience4j) |
| Kafka | The outbox keeps rows unpublished; the poller retries, so nothing is lost and emails arrive late |
| Redis | Idempotency falls back to the Postgres unique constraint; the cache is bypassed |
| Payment provider timeout | The hold stays `HELD` and the payment `PENDING`; a retried confirm resumes the same payment (same provider idempotency key, no second charge); a reconciliation job (with the Stripe adapter) refunds charges whose hold expired |
| Postgres | Hard dependency for booking: refuse (503) rather than guess. In prod: RDS Multi-AZ. Demo: nightly `pg_dump` to S3 with a restore drill |

### Observability
- **Metrics** (Micrometer → Prometheus → Grafana): `reservations_held_total`, `reservations_confirmed_total`, `reservation_sold_out_total`, `holds_expired_total`, idempotency replays, and search latency histograms.
- **Traces**: OpenTelemetry Java agent; one trace spans HTTP → SQL → Kafka publish → consumer.
- **Logs**: JSON with `traceId`; shipped to Loki (cheaper on k3s than a full ELK stack, see trade-offs).
- **Alerts**: oversell attempts at the DB constraint (should always be 0), outbox lag > 60 s, error rate > 1 %.

### Scaling further (designed, not built)
Shard by `hotel_id`, since a reservation never spans hotels so shards never need to talk to each other. Add read replicas for hotel pages. Run active-passive in a second region with geo-routing (video chapter 1:45:19).

---

## Step 5 — Trade-offs and extensions

| Decision | Chosen | Gave up | Why |
|---|---|---|---|
| Service shape | **Modular monolith** (Spring Modulith) | Independent deploys per service | One team, one DB transaction for hold plus outbox. Module boundaries are verified in tests, so a split later is mechanical. |
| Inventory model | Counted per night | Per-room assignment | Matches how OTAs sell rooms; far less contention |
| Concurrency | Atomic conditional UPDATE + CHECK | Optimistic retries, distributed locks | Correct under flash-sale contention with a single round trip |
| Search consistency | ES eventually consistent; Postgres authoritative for availability | Pure ES availability | Search may show a room that is just gone; booking never oversells |
| Event publishing | Polling outbox | Debezium CDC | No Kafka Connect to operate; latency ≤ 1 s is fine |
| Logs | Loki | ELK | ~1/5 of the RAM on a free-tier k3s node; Elasticsearch is still demonstrated for search |
| Payment | Fake adapter behind a port | Real Stripe | No money flows in a portfolio app; the port shows where it plugs in |
| Two-step hold/confirm | Yes | One-step book | Payment latency stays outside the inventory lock |

### Extensions (ranked)
1. Stripe test-mode adapter with webhooks plus a reconciliation job.
2. Debezium CDC instead of the polling outbox.
3. Spring AI "describe your trip" search that turns a sentence into ES filters (the GenAI gap).
4. Dynamic pricing from occupancy.
5. Shard by `hotel_id` and add a second region.

---

### System Design Validation Checklist
- [x] NFRs have numbers · [x] capacity math done · [x] every store justified
- [x] SPOFs listed with behaviour · [x] trade-offs state what was sacrificed
- [ ] **Owner approval**
