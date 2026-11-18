# Communications Log
<!-- Tracks key questions, user preferences, handoffs -->
<!-- Format: ### [YYYY-MM-DD HH:MM] PREFERENCE/HANDOFF/QUESTION — Title -->


### [2026-11-17] [UNDERSTAND] — Sprint 0
- Owner: "lets cook" → continue with Sprint 0 per docs/stories-nuzul.md.
- Track: 🔴 Full (new project, first code, CI + infra).
- Env vars: already in .env.example (local compose defaults); prod values deferred to Sprint 6. Only new need: SONAR_TOKEN (GitHub secret) at 0.3.
- Toolchain: JDK 25 local (compile with --release 21), Node 22, Docker 27 + compose v2, gh CLI.

### [2026-11-18] [UNDERSTAND] — Story 1.1 Flyway V1 schema + InventoryIntegrityIT
- Scope: V1 migration from docs/database-nuzul.md §2 + integrity IT asserting §4 refusals (oversell, negative reserved, check_out<=check_in, dup idempotency key, unknown status).
- Track: Standard — data migration (never Quick); design already fixed in docs, so no doc chain.
- Env vars: none new (DB_URL/DB_USER/DB_PASSWORD already in .env.example).
- Lead: DBA. Open decision: how to create room_inventory monthly partitions.
