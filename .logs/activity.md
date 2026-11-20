# Activity Log
<!-- Tracks completed tasks, milestones, and deliverables -->
<!-- Format: ### [YYYY-MM-DD HH:MM] COMPLETED/MILESTONE — Title -->


### [2026-11-16] [UNDERSTAND] Market scan + CV read; [PLAN] docs drafted (10 files), awaiting owner approval before code.

### [2026-11-16] [SHIP] docs pushed to github.com/rhorba/nuzul (CTS rule 13 complete).

### [2026-11-17] [PLAN] — Sprint 0 confirmed
- Batches: 1) api+web skeletons 2) compose + Keycloak realm 3) CI + Sonar + Trivy + README 4) RAM measure, VERIFY, SHIP.
- Deferred (YAGNI): helm/terraform/E2E CI steps until those folders exist (Sprints 5–6).
- Branch: chore/sprint-0-foundation.

HANDOFF: Scrum Master → Backend Dev
Context: Sprint 0 plan approved; Batch 1 = api skeleton per architecture-nuzul.md §2–3.
Need: Boot 3.5.x / Java 21 skeleton, Modulith modules, ModularityTest + ArchUnit, Spotless, JaCoCo.
Constraints: hexagonal per module; Flyway owns schema (ddl-auto=validate); no business code yet.

### [2026-11-17] [EXECUTE] — CTS plugin installed (owner request)
- `claude plugin marketplace add rhorba/CTS` + `claude plugin install cts@rhorba-cts --scope user` → v2.0.0-beta.1 enabled.
- Hooks/agents active after /reload-plugins or restart. Project-local .claude/skills copies left in place.

### [2026-11-17] [EXECUTE] — 1.1–1.3 api skeleton done
- Boot 4.1.2 (owner chose over 3.5: OSS support ended), Modulith 2.1.1, Java 21 release; 7 modules with package-info; SecurityConfig (JWT RS).
- Tests: ModularityTest, HexagonalArchitectureTest (ArchUnit), app smoke (Testcontainers PG16) → 7/7 green, JaCoCo ≥80% check bound to verify.
- Dropped (YAGNI): modulith observability + starter-jpa (event_publication table not needed; ADR-4 uses own outbox).

HANDOFF: Backend Dev → Frontend Dev
Context: api skeleton green.
Need: web/ Angular latest, standalone + signals, PrimeNG + theme preset, ESLint, Vitest, one smoke test.
Constraints: ADR-6 (no NgRx); i18n/OIDC are Sprint 4 — not now.

### [2026-11-17] [EXECUTE] — 1.4 web skeleton done
- Angular 22.2 standalone, Vitest (2/2), angular-eslint clean, PrimeNG 22 + NuzulPreset (terracotta), build OK.
- Note: initial bundle 521 kB vs 500 kB budget (warning) — lazy routes in Sprint 4.

### [2026-11-17] [EXECUTE] — 2.1–2.3 compose stack done
- compose.yaml: PG 16.16, Redis 7.4.12, ES 8.19.25 (security off, 512m heap), Kafka 4.4.0 KRaft, Keycloak 26.8.2 (+realm import), Mailpit 1.34 — all healthchecks healthy.
- Realm nuzul: roles GUEST/HOTEL_ADMIN/PLATFORM_ADMIN, nuzul-web (public, PKCE S256, aud mapper → nuzul-api), nuzul-api (bearer-only), 2 demo users.
- api boots against stack: health/readiness UP, /api 401 w/o token, /v3/api-docs 200.

HANDOFF: DevOps → DevOps/DevSecOps (CI) 
Context: Batch 2 committed (fe1e605); stack healthy locally.
Need: ci.yml (api verify + Sonar if token, web lint/test/build, Jib + nginx images, Trivy CRITICAL gate, GHCR push on main), README with diagram.
Constraints: actions pinned by SHA; helm/terraform/E2E jobs deferred (folders don't exist yet).

### [2026-11-17] [EXECUTE] — 3.1–3.3 CI, images, README
- .github/workflows/ci.yml: api verify (+Sonar when SONAR_TOKEN set), web lint/test/build, images: Jib tar + nginx → Trivy CRITICAL gate → GHCR multi-arch push on main. All actions pinned by SHA.
- web/Dockerfile: node build → nginx-unprivileged (uid 101) + apk upgrade (fixed brotli CVE-2026-111497); SPA fallback, security headers.
- Local Trivy: api 0 CRITICAL, web 0 CRITICAL after patch. api/mvnw +x.
- SonarCloud (browser, owner-authorised): project rhorba_nuzul imported, Automatic Analysis OFF. SONAR_TOKEN secret: owner to set.
- README with Mermaid architecture diagram.

### [2026-11-17] [EXECUTE] — 0.4 RAM measured
- docker stats (idle, warm): api 389 MB, web 8, PG 48, Redis 4, ES 1,044, Kafka 433, Keycloak 768 → app stack ≈ 3.1 GB.
- Budgets revised: ES 1 → 1.25 GB, Keycloak 512 → 768 MB; plan ≈ 6 GB of 24 GB. k3s budget confirmed (docs/devops-nuzul.md).

### [2026-11-17 13:00] [SHIP] — Sprint 0 foundation
- Summary: branch chore/sprint-0-foundation → PR #1 (https://github.com/rhorba/nuzul/pull/1); merge to main on green CI.
- CI: run 44073782760 GREEN (api 55s, web 29s, images+Trivy 1m53s); SonarCloud skipped — SONAR_TOKEN not yet set.
- Video (rule 9): n/a — no user-facing flows yet (skeleton shell only); first recording at v1.0 (Sprint 6.5).
- Status: complete

### [2026-11-18] [PLAN] — Story 1.1 with pg_partman
- B1 Postgres image (Dockerfile bookworm + postgresql-16-partman, bgw) → compose, Testcontainers, CI/GHCR
- B2 V1__schema.sql (+ partman.create_parent, premake 18, default partition), Failsafe + JaCoCo merge, InventoryIntegrityIT
- B3 docs update, VERIFY (mvn verify, coverage ≥80, Trivy), SHIP (PR + CI green)
- Awaiting owner confirmation.

### [2026-11-18] [EXECUTE] — B1 Postgres image with pg_partman
- deploy/postgres/Dockerfile (postgres:16.16-bookworm + postgresql-16-partman=5.5.*, bgw CMD flags); built locally OK.
- compose.yaml builds it; TestcontainersConfiguration builds same Dockerfile (ImageFromDockerfile, cached) with bgw flags, db/user=nuzul.
- CI images job: build + Trivy + push ghcr nuzul-postgres (amd64+arm64) on main; failsafe reports uploaded.

### [2026-11-18] [EXECUTE] — B2 schema + B3.1 docs
- V1__schema.sql (§2 verbatim + partman.create_parent 1 month, premake 18, default partition); applies in 0.65 s on the image; 7 existing tests green.
- pom: maven-failsafe-plugin (shares JaCoCo argLine/exec → merged coverage).
- InventoryIntegrityIT dispatched to cts:implementer.
- docs: database §2.1 partitioning, devops CI step + CNPG note; risk logged.

### [2026-11-18 11:00] [SHIP] — Story 1.1 Flyway V1 + pg_partman + InventoryIntegrityIT
- Summary: feature/1.1-flyway-v1-schema @ 8df5b65, PR #2
- CI: GREEN (api verify 1m19s, images build+Trivy 2m59s, web 26s)
- Status: complete (awaiting owner merge)

### [2026-11-18 11:15] [QUICK] — Local volume reset + SonarCloud token setup
- Specialist: devops-devsecops
- Understand: owner asked to run `docker compose down -v` and fetch a SonarCloud token via Chrome.
- Approach: reset volumes and start Postgres on the new image; open SonarCloud in Chrome.
- Result: Postgres 16.16 + pg_partman_bgw running fresh. SonarCloud project rhorba_nuzul exists (last analysis 1 day ago, likely Automatic Analysis); browser is logged out, so the owner logs in, generates the token and sets the GitHub secret.
- Verify: psql version + shared_preload_libraries checked; token not handled by the assistant.
- [2026-11-18 11:25] SonarCloud Analysis Method checked (owner logged in): Automatic analysis already Off, no change made. SONAR_TOKEN secret left to the owner (assistant does not enter tokens).
- [2026-11-18 20:00] CI re-run on PR #2 (run 44274831202): all jobs GREEN; SonarCloud scan ran with SONAR_TOKEN, QUALITY GATE STATUS: PASSED.

### [2026-11-19 09:30] [PLAN] — Story 1.2 hotel + room-type CRUD (5 pts), approved by owner
- B1 security plumbing (realm-role converter, method security, problem+json) · B2 hotels (domain/ports/JPA/web + HotelAdminIT cross-owner) · B3 room types (+ RoomTypeAdminIT cross-hotel) · B4 verify/ship.
- Execution: B1 inline; B2/B3 via cts:implementer + spec/quality reviewers.

### [2026-11-19 09:55] [EXECUTE] — 1.2 Batch 1 security plumbing
- KeycloakRealmRoleConverter (realm_access.roles → ROLE_*), wired into SecurityConfig; @EnableMethodSecurity; spring.mvc.problemdetails.enabled.
- Test-first: KeycloakRealmRoleConverterTest red (compile) → green 4/4. Unit suite 11/11 green (Docker Desktop had to be started).

### [2026-11-19 10:30] [EXECUTE] — 1.2 Batch 2 hotels CRUD (cts:implementer)
- catalog domain/application/adapters + shared/error + shared/web/ApiExceptionHandler; /api/v1/admin/hotels POST/GET/PUT/list, owner scope in SQL (findByIdAndOwnerSub), 404 HOTEL_NOT_FOUND cross-owner, size>100 → 400.
- verify green: 34 unit + 35 IT; line coverage 97.7%, branch 88.9%.
- Spec review: PASS (8/8). Quality review: PASS, 0 critical/high; 3 medium (IT cleanup FK vs room_type, null amenity element → 500, lat/lon scale >6 echoed) + 5 low (sort tiebreak, code missing on some 400s, converter not exercised in IT, no @Size bounds, dead problemdetails property).

### [2026-11-19 11:45] [EXECUTE] — 1.2 Batch 3 room types + 8 hardening fixes (cts:implementer)
- /api/v1/admin/hotels/{hotelId}/room-types POST/GET/PUT; scope = @PreAuthorize + scoped parent load + JPQL exists(h.ownerSub=:owner); hotel_id updatable=false.
- Hardening 1–8 applied (random IT owners, amenity element + @Size + @Digits validation, sort tiebreak, code on every 400, converter @Bean, dead property removed).
- Spec review PASS (B6 test only exercised converter, not chain) → orchestrator rewrote RealmRoleWiringIT with @MockitoBean JwtDecoder + real Bearer header; mutation-checked (dropping KeycloakRealmRoleConverter → 403, test fails).
- Quality review PASS, 0 critical/high. Medium (capacity upper bound not in domain) + low (blank bedConfig) fixed inline test-first: RoomType.MAX_CAPACITY=20 shared with @Max.
- Deferred lows (for 1.3): Persistable to avoid merge SELECT on bulk paths; `code` absent on 405/415; RoomTypeRepositoryPort needs findByIdAndOwner without hotelId + catalog @NamedInterface if inventory module checks ownership; room-type name-tie ordering untested.

### [2026-11-19 12:25] [SHIP] — Story 1.2 hotel + room-type admin CRUD
- Summary: feature/1.2-hotel-roomtype-crud @ e79773c (f853028 + CSRF S4502 fix), PR #3
- CI: run 44501525712 RED (SonarCloud security rating, java:S4502) → fixed → run 44502977453 GREEN (api verify+Sonar 2m6s, images build+Trivy 3m40s, web 22s)
- Status: complete (awaiting owner merge)

### [2026-11-19 12:55] [PLAN] — Story 1.3 bulk inventory + rates (5 pts), approved by owner
- B1 catalog RoomTypeOwnership API + Clock/APP_TIME_ZONE + inventory bulk upsert (all-or-nothing 409, window [today,today+180)) + InventoryBulkIT · B2 rates bulk + RateBulkIT · B3 verify/ship.
- Execution: implementer + spec/quality reviewers per batch.

### [2026-11-19 13:35] [EXECUTE] — 1.3 Batch 1 ownership API + inventory bulk (cts:implementer)
- catalog.RoomTypeOwnership (public module API), shared ClockConfig (APP_TIME_ZONE), ConflictException 409, inventory domain/app/JDBC upsert/web; PUT /api/v1/admin/room-types/{id}/inventory.
- verify green: 82 unit + 97 IT; line 99.1%, branch 87.0%. Rollback mutation-checked by implementer.
- Spec review PASS (10/10). Quality review PASS: upsert re-checks latest `reserved` under RC (no total<reserved path); ON CONFLICT on partitioned table OK.
- Medium #1: request body validated before @PreAuthorize → GUEST gets 400 not 403 on all admin endpoints (1.2 too). Medium #2 (for 1.4): reserve() must lock nights in ascending order (ordered FOR NO KEY UPDATE CTE + rowcount check + 40P01 retry + concurrency IT). Lows: unmapped DataIntegrityViolation → 500 (unreachable today), no concurrent-writer IT, CodedException.properties null-safety.

### [2026-11-19 14:40] [EXECUTE] — 1.3 Batch 2 rates bulk + admin authz ordering (cts:implementer)
- PUT /api/v1/admin/room-types/{id}/rates (BigDecimal end to end, String[]→numeric[] upsert, free_cancel_hours untouched); NightBatch shared validator; RateRepositoryPort separate from inventory port (no guard, no RETURNING).
- URL-level hasRole(HOTEL_ADMIN) on admin resources + @PreAuthorize kept; GUEST-invalid-body→403 regression tests in all 4 admin ITs; mutation-checked by orchestrator (rule disabled → 8 cases 400).
- Spec review PASS. Quality review PASS, 0 critical/high. Fixed inline: matcher narrowed to /admin/hotels/** + /admin/room-types/** (future PLATFORM_ADMIN reindex), RateChange comment corrected (@Digits counts trailing zeros) + 850.000→400 test, sci-notation round-trip tests (1E+3, 8.5E+2, 1E+8→400), shared NightBatch.MAX_SIZE.
- Carried forward: shared IT test-support class (1.4); hold must refuse nights without rate row; cancel checks refundable before free_cancel_hours; reserve()/expiry ordered FOR NO KEY UPDATE CTE + 40P01 retry + concurrency IT (1.4).

### [2026-11-19 15:20] [SHIP] — Story 1.3 bulk inventory + rates
- Summary: feature/1.3-bulk-inventory-rates @ c8637ff, PR #4
- CI: run 44515874289 RED (unit tests tzdata-dependent: Africa/Casablanca offset differs CI vs local) → fixed → run 44516405302 api+web GREEN, images RED (transient Maven plugin resolution for jib, no code change) → job rerun GREEN (api verify+Sonar 1m44s, images build+Trivy, web 25s)
- Status: complete (awaiting owner merge)

### [2026-11-19 15:50] [PLAN] — Story 1.4 reserve() guard (5 pts), approved by owner
- B1: shared IT support + StayRange + InventoryReservations.reserve() (ordered FOR NO KEY UPDATE CTE + conditional UPDATE, MANDATORY tx, 409 SOLD_OUT) + LastRoomConcurrencyIT + MultiNightAtomicityIT + ReserveDeadlockFreedomIT + doc updates. B2: verify/ship (rebase on main after PR #4).

### [2026-11-19 16:50] [EXECUTE] — 1.4 reserve() guard + release gates (cts:implementer), review round 1
- InventoryReservations (MANDATORY) + SoldOutException + StayRange/RoomCount + ordered-lock CTE; LastRoomConcurrencyIT, MultiNightAtomicityIT, ReserveDeadlockFreedomIT, InventoryReservationsIT; AdminApi test support; docs updated. 138 unit + 150 IT, line 99.3%.
- Implementer mutation: guard predicate removed → both gates fail (good); plain range UPDATE → deadlock IT still passes (does not discriminate).
- Spec review PASS. Quality review PASS but Medium #1: guard predicate evaluated on statement-snapshot row, not the locked latest version → concurrent capacity release yields FALSE SOLD_OUT (reproduced on PG16 scratch; never oversells). Medium #2: outer UPDATE lacks night range → no partition pruning. Lows: deadlock IT not a regression guard (heap order == night order), Hikari 10 caps contention, rule 5 should order (room_type_id, night), Javadoc for 2.1 tx constraints, nightList unused, shared test imports inventory, seed not replayable.

### [2026-11-19 19:30] [EXECUTE] — 1.4 guard final (review rounds 2–3)
- Final SQL: ordered FOR NO KEY UPDATE CTE + `SET reserved = l.reserved + :rooms, total = l.total` + guard on l + night range on r (owner decision A). ReserveLockWaitIT (5 cases incl. release / raise / lower while waiting), ReservePlanIT (pruning 38 → 1 partition), deadlock IT now a real regression guard (plain UPDATE → 40P01 assertion within 1.4 s), Hikari 50, docs rules 5–6.
- Quality re-review PASS: 11 interleavings probed on PG16, no oversell / 23514 / total change; 4 lows fixed.

### [2026-11-19 19:50] [SHIP] — Story 1.4 reserve() guard + release gates 1–2
- Summary: feature/1.4-reserve-guard @ d6be4d4, PR #5 (stacked on PR #4; auto-retargets to main when #4 merges)
- CI: GREEN first run (api verify+Sonar, images build+Trivy, web)
- Status: complete (awaiting owner merge of #4 then #5)

### [2026-11-20] [PLAN] — Story 1.5 seed (awaiting confirmation)
- B1 boundaries: seed module + type-level @NamedInterface on 4 admin use cases + types; pin Keycloak admin id; inventory read stockedNights(); env APP_SEED_ENABLED / APP_SEED_OWNER_SUB.
- B2 seeder: SeedCatalog (6 cities x 10 hotels, 2-4 room types), DemoSeeder ApplicationRunner, idempotent + top-up missing nights (rates before totals).
- B3 tests: SeedCatalogTest, DemoSeederIT (counts, rerun no-op, clock+1 top-up, admin edits kept), ModularityTest green.

### [2026-11-20] [EXECUTE] — Story 1.5 Batch 1 (boundaries + config)
- New module ma.nuzul.seed; @NamedInterface("admin") on 4 admin use cases + HotelDetails/RoomTypeDetails/PageResult; package-level @NamedInterface("domain") on catalog.domain + inventory.domain.
- ManageInventoryUseCase.stockedNights(ownerSub, roomTypeId) → port stockedNights(from, toExclusive), JDBC adapter, in-memory fake.
- Keycloak admin id pinned 5f0c3a1e-…1f35; app.seed.enabled/owner-sub; .env.example APP_SEED_ENABLED/APP_SEED_OWNER_SUB.
- ModularityTest 2/2, HexagonalArchitectureTest 3/3, InventoryAdminServiceTest 11/11 green.

### [2026-11-20] [EXECUTE] — Story 1.5 Batches 2-3 (seeder + tests)
- seed/SeedCatalog (60 fictional hotels, 180 room types, weekend +20%, no randomness), seed/DemoSeeder (ApplicationRunner, @ConditionalOnProperty app.seed.enabled; rates before totals; only nights with no inventory row).
- Tests: SeedCatalogTest 5, InventoryAdminServiceTest +2 (stockedNights), DemoSeederIT 2 (full seed ~4s, rerun no-op, +1 day = 180 nights, admin edit kept).
- Fix: room-type rotation gave 174; shifted to (i+1)%3 → 30/city.

### [2026-11-20 21:55] [CI] — PR #6 green; PR #7 RED (SonarCloud gate)
- PR #7 new_reliability_rating 4 > 1: java:S8696 x2 at SeedCatalog.java:24 (== on DayOfWeek). Fix: switch on getDayOfWeek().
