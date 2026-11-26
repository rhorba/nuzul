# Decision Log
<!-- Tracks architecture decisions, approach selections, tool choices -->
<!-- Format: ### [YYYY-MM-DD HH:MM] ARCHITECTURE/APPROACH/TOOL — Title -->


### [2026-11-16] [BRAINSTORM] Scope and stack
Options: 🟢 compose-only · 🟡 Balanced (monolith + Helm/k3s + Terraform S3/CloudFront + SonarCloud + OTel) · 🔴 microservices on EKS.
**Owner picked 🟡 Balanced.** Chat UI = React + TS. Names: nuzul (hotel), hdra (chat).
Rationale: market data — Angular 35/53, React 21/53, gaps MongoDB 13, AWS 12, Vue 12, SonarQube 9, ES 5–8, Helm 8, WebSocket 6.

### [2026-11-17] [BRAINSTORM] — Sprint 0 scope
- Chosen: Full Sprint 0 (0.1→0.4) this session; latest stable versions (Boot 3.5.x, Modulith 1.4.x, Angular + PrimeNG latest).
- Rejected: skeletons-only (leaves CI red-risk to next session); Sprint 0 + 1.1 (too long for one checkpointed session).

### [2026-11-17] [EXECUTE] — Spring Boot 4.1.2 instead of 3.x
- Initializr no longer offers 3.x; 3.5 OSS support ended mid-2026. Owner chose 4.1.2. Docs + CLAUDE.md updated.

### [2026-11-18] [BRAINSTORM] — room_inventory partitioning (story 1.1)
- Options: 🟢 SQL DO-block window + DEFAULT partition · 🟡 Java migration + scheduled job · 🔴 pg_partman extension
- Chosen: 🔴 pg_partman (owner pick). Accepts custom Postgres image across compose / Testcontainers / CI / k3s.
- Binding §3 rules unchanged.

### [2026-11-18] [DECISION] — gosu CVE in postgres image
- Option B chosen (owner): replace bundled gosu with Debian package; fallback A (.trivyignore) if Debian build also flagged.

### [2026-11-19 09:20] [BRAINSTORM] — Story 1.2 approach
- Options: Simple (service + JPA only) / Balanced (hexagonal domain + ports + adapters) / Comprehensive (+ETag, PLATFORM_ADMIN override, HotelChanged event).
- Chosen by owner: Balanced. Shared baseline: realm-role converter, built-in problem+json, @PreAuthorize, owner-scoped queries (404 not 403), no DELETE (status INACTIVE).
- Rationale: ADR-2 compliance, ArchUnit rules become non-vacuous; Comprehensive items are YAGNI until 3.2 / later.

### [2026-11-19 12:50] [BRAINSTORM] — Story 1.3 ownership boundary
- Options: Simple (inventory SQL joins catalog tables) / Balanced (catalog public RoomTypeOwnership API, inventory upserts own tables) / Comprehensive (+EXISTS in upsert, GET read-back, range payloads).
- Chosen by owner: Balanced. Shared baseline: single-statement upsert with `WHERE reserved <= EXCLUDED.total`, all-or-nothing 409 INVENTORY_BELOW_RESERVED, window [today, today+180) in APP_TIME_ZONE → 400 NIGHT_OUTSIDE_WINDOW.
- Rationale: keeps Modulith boundary verifiable; TOCTOU harmless (owner/hotel_id immutable); read-back is story 5.2.

### [2026-11-19 15:45] [BRAINSTORM] — Story 1.4 reserve() guard
- Options: Simple (doc's plain range UPDATE + 40P01 retry) / Balanced (one statement: ordered FOR NO KEY UPDATE CTE + conditional UPDATE, MANDATORY-tx module API, deadlock-freedom IT, doc updated, retry in 2.1) / Comprehensive (+@Retryable now, release(), oversell metric).
- Chosen by owner: Balanced. Rationale: deadlock-free by lock order not by retry; still one round trip (doc's rejected option (b) was two round trips); retry/release belong with 2.1/2.3/2.4 transaction boundaries.

### [2026-11-19 18:20] [DECISION] — 1.4 guard SQL final form (owner, via blocker protocol)
- Finding: under READ COMMITTED, Postgres evaluates CHECK on a provisional row built from the statement-snapshot tuple before detecting the concurrent update and recomputing; any column taken from r can trip 23514 on a valid sale. Reproduced in ReserveLockWaitIT and raw PG16.
- Options: A) all columns from locked values (`SET reserved = l.reserved + :rooms, total = l.total`) / B) PL/pgSQL two-statement function (migration) / C) keep current (500 on concurrent total raise).
- Chosen by owner: A. Rule amended from "never write total" to "never changes total" (rewritten with the value held under our own row lock).

### [2026-11-20] [BRAINSTORM] — Story 1.5 seed approach
- Chosen: 🟡 Java seeder (ApplicationRunner, app.seed.enabled default off) through existing use cases; fixed random seed; idempotent with night top-up; pin Keycloak admin id for owner_sub.
- Rejected: 🟢 Flyway SQL (bypasses domain rules, window goes stale); 🔴 daily top-up job (YAGNI until ShedLock in 2.4).
- Also: new PR feature/1.4-reserve-guard → main to repair stacked-merge gap.

### [2026-11-20] [BRAINSTORM] — Story 2.1 idempotency
- Chosen: 🟡 Redis response cache (GET → replay) + Postgres serializes: reservation row inserted BEFORE reserve(), parallel duplicate waits on UNIQUE(guest_sub, idempotency_key), gets 23505, replays the stored row. Redis SET after commit (24 h); Redis down → PG path only. Drops design step "PENDING NX / 409 IN_PROGRESS" (conflicts with gate 3) → update system-design doc.
- Key reuse with different body → 422 IDEMPOTENCY_KEY_REUSED.
- Rejected: 🟢 PG only (no Redis CV signal yet); 🔴 NX lock + polling (flaky gate 3).

### [2026-11-21] [BRAINSTORM] — Story 2.2 confirm flow
- Chosen: 🟡 pay outside any tx + compensate. tx1: HELD & unexpired → begin payment (reuse active PENDING or insert, partial unique index one active per reservation). Charge outside tx, provider idempotency key = payment id. tx2: conditional UPDATE HELD→CONFIRMED where hold_expires_at > now; 0 rows & not CONFIRMED → refund → 410. Declined → FAILED, 402, hold stays HELD.
- Refinement: reuse PENDING instead of 409 PAYMENT_IN_PROGRESS (double-click → one charge, both 200; timeout → resumable).
- Rejected: 🟢 FOR UPDATE across provider call (tx open during external call); 🔴 reconciliation job (YAGNI with fake provider).

### [2026-11-21] [BRAINSTORM] — Story 2.3 cancel
- Policy frozen at hold (V3: reservation.refundable NOT NULL DEFAULT false, free_cancel_until TIMESTAMPTZ NULL; backfill from rates). refundable = every night refundable; deadline = check-in day 00:00 in app zone − strictest free_cancel_hours.
- Cancellable: HELD (early release, no money) and CONFIRMED (refund when free). Rejected: live rates at cancel (admin edits change guest terms); CONFIRMED only.

### [2026-11-21] [BRAINSTORM] — Story 2.4 expiry job
- Chosen: 🟡 ShedLock 7.10.1 (JDBC provider, V4 shedlock table) on a 30 s schedule + per-reservation tx: SELECT … FOR UPDATE SKIP LOCKED (HELD, past hold_expires_at) → conditional HELD→EXPIRED → release. Up to 500 candidates per run (design doc's batch size reinterpreted as a per-run cap: one row per tx avoids cross-room-type deadlocks with cancel/hold).
- Rejected: 🟢 no ShedLock (every pod scans); 🔴 metrics now (stack in 6.3). 7.11.0 skipped (< 2 weeks old).

### [2026-11-23] [DECISION] — Story 2.5 approach: B (balanced)
- Bucket4j+Redis (Lettuce) per-guest bucket, 30 POST/min on /api/v1/reservations/** → 429 RATE_LIMITED + Retry-After.
- Active-hold cap 5 in the hold tx, exact under concurrency via pg_advisory_xact_lock(hashtext(sub)) → 429 TOO_MANY_ACTIVE_HOLDS; idempotent replays answered before the cap.
- Redis down → fail open (matches idempotency cache); cap + 10-min expiry still bound abuse.
- Micrometer counter nuzul.ratelimit.rejected{rule}; limits in application.yml.
- IDOR: traceability table in test strategy pointing at existing per-resource tests. Rejected C (per-IP, tiers, duplicate IDOR matrix) as YAGNI.

### [2026-11-23] [DECISION] — 2.5 interval refill accepted (review L5)
- Bucket refills the full capacity once per period, so a guest can burst up to 2× capacity (60) around a period boundary. Accepted: still bounded, and the active-hold cap limits inventory impact. Add a smaller greedy limit only if smoothing is ever needed.

### [2026-11-24 10:05] [BRAINSTORM] — Story 3.1 approach
- Options: 🟢 ADR-4 poller + idempotent consumer helper | 🟡 + lag gauge, attempts/last_error (V5), consumer DLT, retention cleanup | 🔴 Modulith event externalization (reverses ADR-4)
- Chosen: 🟡 BALANCED (user)
- Why: lag alert (>60 s) and poison-row handling are already in system design; keeps ADR-4

### [2026-11-25] [DECISION] — Story 3.2 approach 🟡
- User picked 🟡: HotelPricesChanged from rate bulk; catalog events keyed by hotelId; synchronous alias-swap reindex (PLATFORM_ADMIN); ES Java client (not Spring Data); search reads via catalog HotelSnapshots + inventory MinPrices public APIs

### [2026-11-25] [DECISION] — Elasticsearch 9.4.8 (was 8.19.25)
- Boot 4.1.2 ships elasticsearch-java/rest5 9.4.8 (sends compatible-with=9; ES 8 rejects it; no 8.x Rest5 client). User chose to move compose + docs to ES 9.4.8.
- ES health indicator disabled (same fail-open reasoning as Redis). User-approved.

### [2026-11-26] [DECISION] — 3.2 B3 reindex live-write safety: catch-up after swap
- Options: A) catch-up after swap (re-upsert hotels with catalog outbox rows created since reindex start − 1 min) / B) listener dual-writes to the in-progress index (needs shared state: migration or ES discovery).
- Chosen by owner: A. Why: no migration, listener stays single-target, correct across replicas (outbox is in Postgres); cost: edits during a reindex reach search seconds later.

### [2026-11-26] [DECISION] — 3.2 B3 search-indexer retries ES outages
- Options: own error handler with capped backoff for ES transport errors / runbook only / defer.
- Chosen by owner: retry longer. Poison payloads still go straight to DLT. Cost: partition blocks while ES is down (acceptable: search is eventually consistent, catalog writes unaffected).
