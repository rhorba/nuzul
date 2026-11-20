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
