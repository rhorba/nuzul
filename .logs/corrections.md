# Corrections & Plan Changes
<!-- Tracks scope changes, pivots, plan adjustments -->
<!-- Format: ### [YYYY-MM-DD HH:MM] SCOPE_CHANGE/PIVOT/REPLAN — Title -->


### [2026-11-17] [EXECUTE] — Sprint 0.2 plan changes
- MinIO dropped from compose: community images no longer published (Docker Hub/Quay). S3 stand-in chosen at Sprint 5 (SeaweedFS or RustFS); prod uses AWS S3, so no RAM-budget impact.
- Postgres host port 5435 (5432–5434 taken by owner's other projects). .env.example + api default updated.
