# Session Log
<!-- Tracks session starts and ends for resumption -->
<!-- Format: ### [YYYY-MM-DD HH:MM] SESSION_START/SESSION_END -->


### [2026-11-16] Session 1 — project inception
**Context:** Owner asked for a hotel reservation app then a chat app, built with CTS, following Hayk Simonyan's
five-step system-design framework, using the stack of on-site/hybrid offers to fill CV gaps.
**Done:** LinkedIn scan (105 offers, 92 read, 53 Java) → gap file `../find a remote freelance job/market-gap-2026-11-16.md`.
BRAINSTORM gate answered: Balanced scope; chat frontend React; repos rhorba/nuzul + rhorba/hdra.
All 10 foundation docs drafted in `docs/` (CTS rule 13).
**Next:** owner approves docs → commit + push docs → Sprint 0. Then the same doc pass for hdra.

### [2026-11-16] SESSION_END
**Done:** owner approved the foundation docs ("ok do it"); repo rhorba/nuzul created (public) and docs pushed.
**Next (open Claude Code in this folder):** Sprint 0 per docs/stories-nuzul.md — 0.1 skeletons (Maven wrapper,
Spring Boot 3 + Modulith, Angular + PrimeNG), 0.2 compose.yaml, 0.3 CI + SonarCloud + Trivy, 0.4 measure RAM of the stack.
Needs from owner at Sprint 0.3: a SonarCloud account linked to GitHub (SONAR_TOKEN secret).

### [2026-11-17] SESSION_START — Session 2
**Resume from:** docs approved + pushed (2529245). Starting Sprint 0 (foundation code).

### [2026-11-17] SESSION_END
**Done:** Sprint 0 complete — api (Boot 4.1.2 + Modulith, 7 tests, 83% cov), web (Angular 22 + PrimeNG), compose (PG:5435, Redis, ES, Kafka, Keycloak realm, Mailpit),
CI (Sonar-ready, Trivy, GHCR multi-arch), README, RAM measured (≈3.1 GB idle; k3s plan ≈6 GB). PR #1 → main.
CTS plugin installed; fixed push-gate bug (rhorba/CTS branch fix/push-gate-command-filter, e7c1f8f, local only).
**Open for owner:** set SONAR_TOKEN secret; decide whether to push/PR the CTS hook fix.
**Next:** Sprint 1 — 1.1 Flyway V1 schema + InventoryIntegrityIT (docs/database-nuzul.md §3 is binding), then 1.2–1.5.

### [2026-11-18] SESSION_START
- Resumed from 2026-11-17 SESSION_END. Owner chose: continue Sprint 1, story 1.1.

### [2026-11-18] SESSION_END
**Done:** Story 1.1. V1 schema with pg_partman (premake 18, DEFAULT partition), custom image deploy/postgres (Debian gosu replaces the CVE-flagged upstream binary), Failsafe + merged JaCoCo (83.3%), InventoryIntegrityIT (10 tests). Spec and quality reviews PASS. PR #2, CI green.
**Open for owner:** merge PR #2; run `docker compose down -v` once locally (Alpine→Debian volume); SONAR_TOKEN still unset.
**Next:** story 1.2 Hotel/room-type CRUD (owner-scoped) + cross-hotel test. Remember story 1.3 must refuse nights beyond the 180-day window (keeps the DEFAULT partition empty).

### [2026-11-19] SESSION_START
- Resumed from 2026-11-18 SESSION_END. PR #2 merged; volume reset + SONAR_TOKEN done (activity log). Branch feature/1.2-hotel-roomtype-crud off main @ bdb9bf1.

### [2026-11-19] SESSION_END
**Done:** Story 1.2. Owner-scoped admin CRUD for hotels (/api/v1/admin/hotels) and room types (/api/v1/admin/hotels/{id}/room-types), hexagonal catalog module, Keycloak realm-role converter (proven through real bearer chain), problem+json with stable codes. 58 unit + 71 IT, line 98.4%. Spec + quality reviews PASS ×2. PR #3, CI green after S4502 CSRF suppression (owner choice A).
**Open for owner:** merge PR #3.
**Next:** story 1.3 bulk inventory + rates under /api/v1/admin/room-types/{id}/... — needs RoomTypeRepositoryPort.findByIdAndOwner (no hotelId) + a catalog @NamedInterface if inventory module checks ownership; refuse total < reserved; refuse nights beyond 180-day window; consider Persistable to avoid merge SELECTs on bulk inserts.

### [2026-11-19] SESSION_END (2)
**Done:** Story 1.3. PUT /api/v1/admin/room-types/{id}/inventory (single guarded upsert, all-or-nothing 409 INVENTORY_BELOW_RESERVED) and /rates (BigDecimal end to end); 180-day window in APP_TIME_ZONE keeps pg_partman DEFAULT empty; catalog.RoomTypeOwnership module API; URL-level admin role rule fixes GUEST-invalid-body 400→403 on all admin endpoints. 112 unit + 139 IT, line 99.2%. Reviews PASS ×4. PR #4 CI green.
**Open for owner:** merge PR #4. Heads-up: CI's newer JDK tzdata resolves Africa/Casablanca differently from local JDK 25 (tzdata 2026a) — consider updating the local JDK.
**Next:** story 1.4 reserve() guard + LastRoomConcurrencyIT + MultiNightAtomicityIT. Must: ordered `SELECT … ORDER BY night FOR NO KEY UPDATE` CTE + conditional UPDATE in one statement, rowcount == nights else rollback, 40P01 bounded retry, concurrency IT vs bulk upsert; extract shared IT support class first. Carry to 2.x: hold refuses nights with no rate row; cancel checks refundable before free_cancel_hours.

### [2026-11-19] SESSION_START (3)
- Story 1.4 on feature/1.4-reserve-guard, stacked on feature/1.3 (PR #4 not merged yet); rebase onto main before opening PR.

### [2026-11-19] SESSION_END (3)
**Done:** Story 1.4. InventoryReservations.reserve() (MANDATORY tx, 409 SOLD_OUT). Final guard: ordered FOR NO KEY UPDATE CTE + `SET reserved = l.reserved + :rooms, total = l.total`, guard on l, night range on r. Found & documented PG READ COMMITTED gotcha (CHECK evaluated on provisional snapshot-based row before EPQ) via 2 failed variants. Release gates LastRoomConcurrencyIT + MultiNightAtomicityIT, ReserveLockWaitIT, ReserveDeadlockFreedomIT (real regression guard), ReservePlanIT. 139 unit + 158 IT, line 99.3%. PR #5 CI green.
**Open for owner:** merge PR #4, then PR #5 (stacked). Sprint 1 remaining: 1.5 seed data.
**Next:** story 1.5 seed (6 cities, ~60 hotels, 180 days inventory) — must use the bulk APIs or respect window + ascending lock rules. Carry to 2.1: 40P01 retry around outer hold tx, refuse nights without rate, retarget gate 1 to "exactly 1 HELD", validate before tx. Carry to 2.4: release takes all columns from locked values (`reserved = l.reserved - n, total = l.total`).

### [2026-11-20] SESSION_START (4)
- Resumed from SESSION_END (3). Target: story 1.5 seed data.
- Found: PR #5 merged into feature/1.3-bulk-inventory-rates AFTER PR #4 merged, so story 1.4 (d6be4d4) is NOT on main.

### [2026-11-20] SESSION_END (4)
**Done:** Found story 1.4 never reached main (stacked PR #5 merged into 1.3 branch after #4) → PR #6 (1.4 → main), CI green. Story 1.5: seed module (DemoSeeder + SeedCatalog), 60 hotels / 6 cities / 180 room types / 32,400 priced nights via admin use cases; idempotent, window top-up on restart, admin edits kept, rates before totals, failure never blocks startup, default off. stockedNights read in inventory. Modulith named interfaces; Keycloak admin id pinned. 148 unit + 161 IT, line 99.5%. PR #7 CI green (after Sonar S8696 fix).
**Open for owner:** merge PR #6 FIRST, then PR #7. Recreate Keycloak container to pick up the pinned admin id.
**Next:** Sprint 2 → story 2.1 Hold (POST /reservations + Idempotency-Key). Carry to 2.1: 40P01 retry around outer hold tx, refuse nights without rate, retarget gate 1 to "exactly 1 HELD", validate before tx. Carry to 2.4: release takes all columns from locked values. Carry to 6.1: advisory lock / single-replica guard for the seeder.

### [2026-11-21] SESSION_END (5)
**Done:** Story 2.1 — POST /api/v1/reservations (GUEST), idempotent per (guest, key): Redis cache (fail-open) → PG key lookup → tx: price (SQL sum, unpriced = SOLD_OUT) → INSERT … ON CONFLICT reservation_idem DO NOTHING → reserve(); whole tx retried on 40P01/40001 (RetryTemplate). 422 IDEMPOTENCY_KEY_REUSED. Gates 1 (hold path) + 3 (incl. Redis down) green. Published catalog.BookableRoomTypes, inventory.StayPricing. Design doc idempotency updated. 174 unit + 180 IT, line 99.3%. PR #8 CI green.
**Open for owner:** merge PR #8.
**Next:** story 2.2 Confirm via PaymentPort / FakePaymentAdapter (card ending 0002 fails), payment OUTSIDE the hold tx; 410 HOLD_EXPIRED, 402 PAYMENT_FAILED.
**Carry:** 2.2/2.4 must never clear hold_expires_at on a row whose replay may be served from PG (or update replay mapping). 2.4 release takes all columns from locked values. 2.5 rate limits + active-hold cap. 3.1 outbox row in the hold tx. Low: Sonar S8786 regex in HoldIT:88; add hanging-Redis timeout test. 6.1 seeder single-replica guard.

### [2026-11-21] SESSION_END (6)
**Done:** Story 2.2 — POST /api/v1/reservations/{id}/confirm: tx1 begin payment (V2 payment_one_active) → charge outside any tx (idempotency key = payment id) → tx2 conditional HELD→CONFIRMED + capture; refund claims PENDING→REFUNDING before the provider, REFUNDED after (never refunds a captured payment). 402/410/404/409. New payment module + FakePaymentAdapter (*0002). Review HIGH (refund vs capture race) fixed + re-reviewed PASS. Redis health contributor disabled. 199 unit + 192 IT, line 99.3%. PR #9 CI green.
**Open for owner:** merge PR #9.
**Next:** story 2.3 Cancel with free-cancellation rule (rate.refundable + free_cancel_hours; refund flag; release inventory in same tx as status change, all columns from locked values per 2.4 carry).
**Carry:** 2.4 expiry job must use conditional HELD→EXPIRED (same semantics as confirmIfHeld), FOR UPDATE SKIP LOCKED, release inventory in same tx; never clear hold_expires_at. Reconciliation job for PENDING/REFUNDING payments (with Stripe adapter). Low: twin losing capture to refund claim answers 410; Sonar S8786 regex HoldIT:88; hanging-Redis test. 2.5 rate limits. 3.1 outbox rows (hold + ReservationConfirmed). 6.1 seeder single-replica guard.
