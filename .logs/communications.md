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

### [2026-11-19 09:10] [UNDERSTAND] — Story 1.2 hotel + room-type CRUD
- Track: Standard (touches auth/authorization; backend + security + tester).
- Scope: /admin/hotels and /admin/hotels/{id}/room-types for role HOTEL_ADMIN, owner scope in SQL (owner_sub = jwt.sub), 404 not 403 on foreign hotel (security doc), cross-hotel test.
- Gaps found: no realm-role → authority mapping yet; no ProblemDetails handler yet; no catalog code yet.
- Env vars: none new.

### [2026-11-19 12:40] [UNDERSTAND] — Story 1.3 bulk inventory + rates
- Track: Standard (authz + inventory integrity; backend + DBA + security + tester).
- Scope: PUT /api/v1/admin/room-types/{id}/inventory [{night,total}] (refuse total < reserved) and PUT .../rates [{night,priceMad,refundable}]; HOTEL_ADMIN, owner-scoped; nights limited to [today, today+180) to keep the pg_partman DEFAULT partition empty (database doc §2.1).
- Module question: inventory owns room_inventory/rate but ownership lives in catalog (closed Modulith module).
- Env vars: new APP_TIME_ZONE (default Africa/Casablanca) for "today"; no secrets.

### [2026-11-19 15:35] [UNDERSTAND] — Story 1.4 reserve() guard + release gates 1–2
- Track: Full-depth Standard (core double-booking guard; release gates LastRoomConcurrencyIT, MultiNightAtomicityIT; DBA + backend + test architect).
- Binding: system-design §"double-booking guard" (one atomic conditional UPDATE, rowcount == nights else 409 SOLD_OUT, ascending night order), database doc §3 (half-open, CHECK backstop).
- Conflict found: the doc's plain range UPDATE does not guarantee ascending lock order (1.3 quality review) → needs ordered FOR NO KEY UPDATE CTE in the same statement; doc to be updated.
- No HTTP endpoint (reservation module is 2.1): guard exposed as inventory module API, tested at that level. No new env vars.

### [2026-11-20] [UNDERSTAND] — Story 1.5 seed data
- Track: Standard (writes inventory/catalog data; must respect booking window + ascending lock rules).
- Scope: 6 cities (Marrakech, Fès, Chefchaouen, Essaouira, Agadir, Tanger), ~60 hotels, room types, 180 days inventory + rates.
- Gaps: Keycloak admin user has no pinned id → seeded owner_sub can't match its JWT sub; story 1.4 not on main (stacked-PR merge order).
- Env vars: one new flag for enabling the seed (default off).
