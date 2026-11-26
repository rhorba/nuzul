# Corrections & Plan Changes
<!-- Tracks scope changes, pivots, plan adjustments -->
<!-- Format: ### [YYYY-MM-DD HH:MM] SCOPE_CHANGE/PIVOT/REPLAN — Title -->


### [2026-11-17] [EXECUTE] — Sprint 0.2 plan changes
- MinIO dropped from compose: community images no longer published (Docker Hub/Quay). S3 stand-in chosen at Sprint 5 (SeaweedFS or RustFS); prod uses AWS S3, so no RAM-budget impact.
- Postgres host port 5435 (5432–5434 taken by owner's other projects). .env.example + api default updated.

### [2026-11-19 10:40] [PLAN CHANGE] — 1.2 Batch 3 scope widened
- Owner chose to fold all 8 Batch 2 quality findings (3 medium, 5 low) into Batch 3 alongside room types.

### [2026-11-19 13:40] [PLAN CHANGE] — 1.3 Batch 2 also fixes admin authz ordering
- Adds URL-level `/api/v1/admin/**` hasRole(HOTEL_ADMIN) (keeps @PreAuthorize) + GUEST-invalid-body→403 regression tests on all admin controllers, comment reword on lock order, CodedException.properties null-safe. Reason: quality-review Medium #1 is a real authz-ordering defect in shipped 1.2 code.
- Medium #2 (reserve lock order) carried to story 1.4.

### [2026-11-19 16:55] [CORRECTION] — 1.4 guard predicate must use locked values
- Design doc claim "re-checked on the latest row version under the lock" was false for rows filtered out on the old snapshot. Fix: predicate on CTE columns (l.reserved/l.total) + night range on r; doc and comment corrected; regression IT for concurrent release. Sent back to implementer with all lows.

### [2026-11-19 17:40] [CORRECTION] — 1.4 guard: reviewer's fix also wrong; adopt SET from locked values
- Reviewer's SQL (guard on l, SET r.reserved + n) turned the false SOLD_OUT into a 23514 CHECK violation on a released room: the new value is built from the stale snapshot tuple of r. Reproduced by implementer in ReserveLockWaitIT and on raw PG16.
- Decision (orchestrator, within approved Balanced approach): `SET reserved = l.reserved + :rooms` + guard on l + night range on r. r only locates the row via immutable columns; l holds the locked latest values; lock held CTE→UPDATE so the value is exact. Doc/comment to state this, drop the EvalPlanQual claim.

### [2026-11-20] [CORRECTION] — Story 1.5 named interfaces
- Plan said type-level @NamedInterface on domain types; that breaks domainHasNoFrameworkDependencies. Switched domain to package-level (package-info) and excluded package-info from that ArchUnit rule only.

### [2026-11-20] [CORRECTION] — Story 2.1 duplicate-key handling
- Plan said catch 23505 then replay outside the tx. Switched to INSERT … ON CONFLICT ON CONSTRAINT reservation_idem DO NOTHING: same wait-on-unique-index behaviour, no aborted transaction, replay read inside the same tx. Spring 7 RetryTemplate (programmatic) instead of @Retryable to make retry-outside-transaction ordering explicit.

### [2026-11-21] [CORRECTION] — Story 2.2 charge/refund idempotent by state
- Plan: charge only PENDING payments. Twin confirms sharing a payment could reach charge after the other captured → 500. Now charge answers from state (CAPTURED/REFUNDED → stored approval, FAILED → declined) and refund on REFUNDED is a no-op; provider_ref keeps the charge ref.

### [2026-11-21] [CORRECTION] — Story 2.2 review HIGH: refund vs capture race
- Found by quality-reviewer: refund read PENDING → provider refund → conditional update; a twin confirm could capture in the gap → CONFIRMED + refunded money. Fix: refund claims PENDING→REFUNDED before the provider call; capture returns boolean and tx2 rolls back (CaptureLost) when the claim won; confirm's `now` read inside tx2; charge answers from state when its guarded update loses; cancelled-during-charge → 409. Tests added (unit) + ConfirmIT asserts a single provider idempotency key.

### [2026-11-21] [CORRECTION] — Story 2.3 review (FAIL → fixed)
- HIGH: concurrent cancels (or a cancel overlapping confirm's refund) could both call provider.refund for one claimed refund. Exactly-once from our side is impossible outside a tx; fixed the standard way: PaymentPort.refund takes a required idempotency key ("refund-" + payment id), written into its contract; fake + stand-ins dedupe; CancelIT asserts distinct refund keys.
- MEDIUM: free cancel with no captured payment now logs ERROR. Added CancelIT cancel-during-charge race (cancel wins → confirm 409 → REFUNDED). DST unit test (America/Santiago midnight gap).
- LOW: V3 backfill sets free_cancel_until only for refundable rows; zone hard-code documented. Carry to 2.4: expiry must process one reservation per tx (reservation row → its inventory), never batch-lock several reservation rows.

### [2026-11-25] [CORRECTION] — 3.2 B3 reindex must not lose live writes
- Quality review of B1 (M3): listener writes during a reindex go to the old index and are dropped at the swap. B3 plan change: while the reindex holds its advisory lock, the listener also writes to the in-progress index (or reindex re-applies changes after swap). Replaces the "accepted limit" in the 3.2 plan.

### [2026-11-26] [CORRECTION] — 3.2 B3 resolves the 2026-11-25 live-write correction via catch-up (not dual-write); B3 also carries its ITs (implementer is test-first), B4 = docs + VERIFY + ship
