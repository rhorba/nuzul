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

### [2026-11-20 22:10] [CI] — PR #7 GREEN after S8696 fix (api verify + Sonar, web, images all pass); PR #6 GREEN.

### [2026-11-20 22:10] [SHIP] — Story 1.5 seed data (Sprint 1 complete)
- Summary: feature/1.5-seed @ 7f92eae pushed; PR #7 → main (depends on PR #6 = story 1.4 onto main).
- Status: complete

### [2026-11-20] [PLAN] — Story 2.1 Hold (awaiting confirmation)
- B1 published APIs: catalog BookableRoomTypes.hotelOf (ACTIVE only); inventory StayPricing.price (SQL sum, refuse missing rate → SOLD_OUT).
- B2 reservation core: HoldRequest validation pre-tx; HoldTransaction (@Transactional + @Retryable 40P01/40001): price → INSERT reservation (HELD) → reserve(); JDBC adapter; 23505 → replay or 422 IDEMPOTENCY_KEY_REUSED; controller POST /api/v1/reservations (GUEST).
- B3 Redis: starter-data-redis, short timeouts, IdempotencyCache GET/SET after commit, fail-open; Testcontainers redis.
- B4 gates: LastRoomConcurrencyIT retargeted to hold path (exactly 1 HELD) + old reserve-level kept as ReserveLastRoomIT; IdempotencyIT (5x parallel, Redis down); HoldIT (API contract, validation, auth); design doc update.

### [2026-11-20] [EXECUTE] — Story 2.1 Batch 1 (published lookups)
- catalog.BookableRoomTypes.hotelOf (ACTIVE hotels only) + adapter/JPQL; inventory.StayPricing.price (SQL count+sum over [in,out), missing rate → SOLD_OUT) + RateRepositoryPort.priceNights.
- StayPricingServiceTest 4/4, BookingLookupsIT 4/4, Modularity/Hexagonal green.

### [2026-11-20] [EXECUTE] — Story 2.1 Batch 2 (reservation core)
- reservation module: domain (HoldRequest, Guest, Reservation, ReservationStatus), HoldService (pre-tx validation + window, PG key pre-check, RetryTemplate on PessimisticLockingFailureException ×3), HoldTransaction (price → INSERT … ON CONFLICT ON CONSTRAINT reservation_idem DO NOTHING → reserve), JdbcReservationRepository, ReservationController POST /api/v1/reservations (GUEST, Idempotency-Key UUID header, guest from JWT name/email claims).
- shared: UnprocessableException → 422; SecurityConfig /api/v1/reservations/** hasRole GUEST.
- HoldServiceTest 12/12.

### [2026-11-20] [EXECUTE] — Story 2.1 Batches 3-4 (Redis cache + gates)
- Redis: spring-boot-starter-data-redis (timeouts 300ms), IdempotencyCache port + RedisIdempotencyCache (v1 pipe line, 24h TTL, fail-open); HoldService order: Redis → PG key → window → hotel → retried tx → cache put. Testcontainers redis service connection.
- Gate 1 retargeted: reservation.LastRoomConcurrencyIT through the hold (200 guests → exactly 1 HELD, no stray rows; 50 → 5 over 3 nights); inventory-level kept as ReserveLastRoomIT.
- Gate 3: IdempotencyIT 3/3 + IdempotencyRedisDownIT 3/3 (5 parallel → 1 row, 5 identical 201s). HoldIT 7/7. RedisIdempotencyCacheTest 5, HoldServiceTest 16.
- docs/system-design §Idempotency updated (PENDING/IN_PROGRESS step removed).

### [2026-11-21 00:00] [CI] — PR #8 GREEN (api verify + Sonar gate, web, images). Non-blocking Sonar MAJOR java:S8786 (backtracking regex) in HoldIT.java:88 test helper.

### [2026-11-21 00:00] [SHIP] — Story 2.1 Hold + idempotency
- Summary: feature/2.1-hold @ 3e8e573 pushed; PR #8 → main, CI green.
- Status: complete

### [2026-11-21] [PLAN] — Story 2.2 Confirm (awaiting confirmation)
- B1 payment module: V2 migration (status CHECK + partial unique index one active payment per reservation), Payments API (begin/charge/capture/refund), JdbcPaymentRepository, PaymentPort + FakePaymentAdapter (token *0002 declines, deterministic refs), tests.
- B2 reservation confirm: ConfirmService + ConfirmTransactions (tx1 begin, tx2 conditional confirm), POST /api/v1/reservations/{id}/confirm, 402/410 mapping, owner-scoped load (404 for others).
- B3 ConfirmServiceTest, ConfirmIT (happy, 402→retry, 410, IDOR 404, cancelled 409, replay, 5x parallel = 1 capture, expiry-during-charge → refund+410), docs.

### [2026-11-21] [EXECUTE] — Story 2.2 Batches 1-3 (payment module, confirm, tests, docs)
- payment: V2__payment_guard.sql (status CHECK + payment_one_active partial unique index); Payments API (begin MANDATORY / charge+refund outside tx, guarded / capture MANDATORY), PaymentService (charge/refund idempotent by state), JdbcPaymentRepository, PaymentPort + FakePaymentAdapter (*0002 declines, refs from idempotency key).
- reservation: ConfirmService (owner-scoped load, status gate, tx1 start → charge outside tx → tx2 confirmIfHeld+capture → refund+410 if expired meanwhile), ConfirmTransactions, POST /api/v1/reservations/{id}/confirm; shared GoneException 410, PaymentRequiredException 402.
- Tests: PaymentServiceTest 8, FakePaymentAdapterTest 3, ConfirmServiceTest 10, PaymentIT 5, ConfirmIT 7 (incl. 5x parallel = 1 capture, expiry during charge → REFUNDED + 410).
- docs: system design "Confirm and payment" + failure table; database doc V2.

### [2026-11-21 15:00] [CI] — PR #9 GREEN (api verify + Sonar gate, web, images); no Sonar issues.

### [2026-11-21 15:00] [SHIP] — Story 2.2 Confirm via PaymentPort
- Summary: feature/2.2-confirm @ 62a4c75 pushed; PR #9 → main, CI green.
- Status: complete

### [2026-11-21] [PLAN] — Story 2.3 Cancel (awaiting confirmation)
- B1 policy: V3 migration + backfill; StayPricing.quote (total + refundable + strictest hours); CancellationPolicy domain; hold stores it.
- B2 inventory: InventoryReservations.release (MANDATORY, ordered FOR NO KEY UPDATE CTE, all columns from locked values, guard).
- B3 payments: claimRefund (CAPTURED→REFUNDING, MANDATORY), finishRefund (provider → REFUNDED), refund status queries; existing refund refactored onto them.
- B4 cancel: CancelService + tx (conditional status → CANCELLED + release + claim in one commit; provider refund after commit), POST /{id}/cancel, idempotent replay (retries a REFUNDING refund).
- B5 tests (CancellationPolicyTest, CancelServiceTest, InventoryReleaseIT, CancelIT incl. races) + docs.

### [2026-11-21] [EXECUTE] — Story 2.3 Batches 1-4 (policy, release, refunds, cancel)
- V3 migration (reservation.refundable, free_cancel_until + backfill); StayPricing.quote → StayQuote (total, all refundable, strictest hours); CancellationPolicy domain; hold freezes it.
- InventoryReservations.release (MANDATORY; RELEASE SQL mirrors RESERVE: ordered FOR NO KEY UPDATE CTE, all columns from locked values, guard reserved >= rooms; partial = IllegalStateException).
- Payments: claimRefund (CAPTURED→REFUNDING, MANDATORY), finishRefund (provider → REFUNDED), refundOf; confirm-path refund rebuilt on send().
- CancelService + CancelTransaction (conditional HELD|CONFIRMED→CANCELLED + release + claim in one commit; provider after commit; provider error → refund PENDING, retried by next cancel); POST /api/v1/reservations/{id}/cancel; two lost races → 409 CONCURRENT_UPDATE.
- Unit: InventoryReservationServiceTest +3, PaymentServiceTest 13, existing suites green.

### [2026-11-21] [EXECUTE] — Story 2.3 Batch 5 (tests + docs)
- CancellationPolicyTest 5, CancelServiceTest 9, InventoryReservationServiceTest +3, PaymentServiceTest +3, CancelIT 8 (frozen policy, free refund + release, non-refundable, past deadline, held cancel → confirm 409, provider error → PENDING → retry COMPLETED, 5x parallel = one release/refund, 404/410/401), InventoryReleaseIT 4 (all-or-nothing, MANDATORY, 40 concurrent reserve/release no deadlock/lost update).
- Fix during tests: double refund send on the first cancel call removed (outcome() owns sending).
- docs: system design "Cancel (story 2.3)" + API table; database V3.

### [2026-11-21 18:30] [CI] — PR #10 GREEN (api verify + Sonar gate, web, images); no Sonar issues.

### [2026-11-21 18:30] [SHIP] — Story 2.3 Cancel with free-cancellation rule
- Summary: feature/2.3-cancel @ 6fa40d9 pushed; PR #10 → main, CI green.
- Status: complete

### [2026-11-21] [PLAN] — Story 2.4 Expiry (awaiting confirmation)
- B1 ShedLock deps + V4__shedlock.sql + SchedulingConfig (@EnableScheduling, @EnableSchedulerLock, JdbcTemplateLockProvider usingDbTime); app.expiry.* props (interval 30s, batch 500, enabled).
- B2 ExpireHoldsUseCase: repo expiredHoldIds(now, limit) (index reservation_expiry), ExpiryTransaction.expire(id) (FOR UPDATE SKIP LOCKED + conditional + release), HoldExpiryJob (@Scheduled + @SchedulerLock).
- B3 tests: ExpireHoldsServiceTest, HoldExpiryIT (gate 4: two schedulers racing, 50 holds → each released once; expiry vs confirm race; vs cancel; not-yet-expired untouched; hold_expires_at kept), ShedLock wiring IT; docs.

### [2026-11-22] [PLAN] — Story 2.4 Expiry: confirmed by user ("go")
- Proceeding to EXECUTE Batch 1. Handoff Tech Lead → Backend Dev: plan above; one reservation per tx, SKIP LOCKED, ShedLock JDBC with DB time; job disabled in tests by default (src/test/resources), HoldExpiryIT drives the use case directly.

### [2026-11-22] [EXECUTE] — Story 2.4 Batches 1-3 (ShedLock, expiry, tests, docs)
- B1: shedlock-spring + provider-jdbc-template 7.10.1; V4__shedlock.sql (TIMESTAMPTZ); shared/scheduling/SchedulingConfig (@EnableScheduling, @EnableSchedulerLock PT5M, JdbcTemplateLockProvider usingDbTime); app.expiry.{enabled,interval PT30S,batch-size 500} + .env.example; src/test/resources/application.properties turns the job off in ITs.
- B2: port expiredHoldIds/lockExpiredHold (FOR UPDATE SKIP LOCKED)/expireIfHeld; ExpiryTransaction (lock → conditional HELD→EXPIRED → release, one tx per reservation); ExpireHoldsService (batch cap, per-hold failure isolation); HoldExpiryJob (adapter.in.scheduling, @Scheduled fixed delay + @SchedulerLock "hold-expiry").
- B3: ExpireHoldsServiceTest 7; HoldExpiryIT 5 (gate 4: 4 racing runs over 50 holds → released once; running hold untouched; confirm holding the row → skipped without waiting, stays CONFIRMED; 20 cancels racing 2 runs → each released once, no deadlock; expired hold can't be confirmed/cancelled); HoldExpirySchedulingIT 1 (job expires a due hold, shedlock row written). Docs: system design "As built (story 2.4)", database V4.
- Issue: Docker Desktop was not running (Testcontainers failure); started it, ITs green.

### [2026-11-22 12:45] [CI] — PR #11 GREEN (api verify + Sonar gate 3m29s, web 25s, images + Trivy 3m53s), run 45004601050.

### [2026-11-22 12:45] [SHIP] — Story 2.4 Hold expiry (ShedLock + SKIP LOCKED)
- Summary: feature/2.4-expiry pushed; PR #11 → main, CI green. Release gate 4 (HoldExpiryIT) in place.
- Status: complete

### [2026-11-23] [PLAN] — Story 2.5 rate limits (approach B) — awaiting confirmation
- HANDOFF Orchestrator → Scrum Master: B chosen (Bucket4j/Redis 30 POST/min/guest fail-open; exact 5-active-hold cap via advisory xact lock; metric; IDOR traceability). Need: batches.
- B1 shared: TooManyRequestsException(429, Retry-After) + RateLimiter port + Bucket4j Lettuce adapter (fail-open, own timeouts) + metric + props.
- B2 reservation: interceptor on POST /api/v1/reservations/** ; hold cap in HoldTransaction (lock → key replay → count → insert).
- B3 tests (RateLimitIT, ActiveHoldCapIT incl. concurrency, Redis-down, unit) + docs (security §2, test-strategy IDOR table, application.yml).

### [2026-11-23] [EXECUTE] — 2.5 Batch 1 shared rate limiter
- Implementer: TooManyRequestsException→429+Retry-After; RateLimiter port; RedisRateLimiter + LettuceBucketStore (own client, 300ms deadline, fail-open); RateLimitProperties/Config; metric nuzul.ratelimit.rejected{rule}; .env.example.
- Tests: unit 13 new pass; RedisRateLimiterIT 5/5 (Docker started by orchestrator).
- Spec review: PASS. Quality review: PASS (0 crit/high). Open: M1 rediss:// URL not honoured → limiter silently fails open; M2 WARN per request during outage; M3 second Lettuce ClientResources; L4 conn leak if proxyManager throws; L5 interval refill allows 2x burst at boundary (by design); L6 subject must come from JWT; L7 RedisURI mutated.
- [2026-11-23] Batch 1 review fixes: M1 rediss→TLS, M2 throttled WARN/INFO (incl. flapping) + 5s reconnect back-off, M3 shared ClientResources (direct injection), L4 conn close, L7 URI builder, monotonic nanoTime. Re-review PASS. Committed locally.

### [2026-11-23 12:50] [CI] — PR #12 checks
- Run 45171857588: RED — SonarCloud PR auto-config "Illegal base64 character 2d" (SonarCloud-side binding); rerun same. Fix: ci.yml passes sonar.pullrequest.* explicitly (6ffed86).
- Run 45175880236: RED — quality gate: new_reliability_rating (S3518 div-by-zero false positive in ceilSeconds; S3516 preHandle always true). Fix 3adc33a (Math.ceilDiv, single-return preHandle); affected unit tests 14/14.
- Run 45176620257: GREEN — api verify + SonarCloud gate OK, web, images + Trivy.

### [2026-11-23 12:50] [SHIP] — Story 2.5 rate limiting + exact active-hold cap
- Summary: feature/2.5-rate-limits @ 3adc33a pushed; PR #12 open, CI green, Sonar gate OK. Coverage 98.6% lines.
- Status: complete

### [2026-11-23] [CI] — main after PR #12 merge (1f54fb1)
- Run 45181834615: GREEN — api verify + SonarCloud, web, images build + Trivy + push. Sprint 2 complete (stories 2.1–2.5).

### [2026-11-24 10:00] [UNDERSTAND] — Story 3.1 outbox → Kafka
- Track: Standard (cross-module: shared infra + reservation + catalog txns; touches DB/messaging)
- Found: `outbox_event` + `processed_event` already in V1; nothing writes them; no Kafka deps in api/pom.xml; Kafka (apache/kafka 4.4.0, KRaft) already in compose.yaml
- Events to emit: ReservationHeld/Confirmed/Cancelled/Expired (4 @Transactional sites) → `reservation-events`; hotel/room-type changes → `catalog-events`
- Env: KAFKA_BOOTSTRAP_SERVERS (new)
- Blocker noted: Docker Desktop not running (needed for ITs)

### [2026-11-24 10:15] [PLAN] — Story 3.1 outbox → Kafka (🟡) — confirmed by user
- B1 foundation (deps, V5 attempts/last_error/aggregate_id, OutboxPort MANDATORY tx) · B2 emit reservation+catalog events · B3 relay job (SKIP LOCKED, per-row failure, lag/failed gauges, retention) · B4 IdempotentConsumer + DLT + placeholder consumer · B5 ITs, docs, VERIFY, ship
- Payloads: ids/status/money-as-string, no guest PII; branch feature/3.1-outbox

### [2026-11-24 10:50] [EXECUTE] — 3.1 Batch 1 foundation done
- 1.1 spring-boot-starter-kafka (+ testcontainers-kafka, kafka-test); spring.kafka (acks=all, idempotent, 5-10 s timeouts); app.outbox.* + .env.example; KafkaContainer apache/kafka:4.4.0 in TestcontainersConfiguration; app.outbox.enabled=false in test props
- 1.2 V5__outbox_relay.sql (aggregate_id UUID NOT NULL, attempts CHECK >= 0, last_error); database-nuzul.md §2 updated
- 1.3 shared/outbox: Aggregate (column→topic), Topics, OutboxPort, JdbcOutbox (MANDATORY tx, Jackson 3 JsonMapper, ::jsonb)
- Check: compile + test-compile OK; 256/258 unit pass, 2 errors = NuzulApiApplicationTests needs Docker (not running)

### [2026-11-24 11:40] [EXECUTE] — 3.1 Batch 2 emit events done (implementer → spec PASS → quality PASS)
- ReservationEvent (public, no PII; totalMad plain string) appended in Hold (new only, after reserve), Confirm (after capture; finish now takes Reservation), Cancel (after cancelIf), Expiry (after release); HotelChanged/RoomTypeChanged in catalog admin services (create/update)
- Quality lows fixed: replay IT bypasses Redis cache to hit DB replay path; HotelAdminService.createdAt from Clock; expiry IT case added
- Unit: 274 run, 0 fail, 2 errors (NuzulApiApplicationTests — Docker down). OutboxEventsIT (5 tests) compiles, NOT yet run
- [2026-11-24 12:05] B1+B2 full verify with Docker: 274 unit + 231 IT, 0 failures (OutboxEventsIT green)

### [2026-11-24 13:45] [EXECUTE] — 3.1 Batch 3 relay done (implementer → spec PASS → quality FAIL r1 → fixes → quality PASS r2)
- shared/outbox: OutboxRelay (1 txn/run, SKIP LOCKED, sequential send+ack, allowlist poison classification, same-aggregate skip, run-budget PT30S, budget+send+10 s margin < lockAtMostFor PT1M), OutboxRelayJob, OutboxRetentionJob (cron, P7D), OutboxMetrics (outbox.lag.seconds, outbox.failed), OutboxTopicsConfig; JdbcOutbox created_at = clock_timestamp()
- Orchestrator fix: LOCK_MARGIN 10 s on run-budget check (r2 low)
- Tests: 295 unit + 237 IT green (implementer verify); Outbox*Test 21/21 after margin fix
- Carry: gauge scrape may block on exhausted pool (prometheus not exposed yet)

### [2026-11-25 11:50] [EXECUTE] — 3.1 Batch 4 consumer side done (spec FAIL r1 → fix → quality PASS → fixes)
- shared/outbox: EventHeaders (event-id/event-type shared with relay; type must be [A-Za-z]{1,64}; quoted values truncated + control chars stripped), IdempotentConsumer (processed_event ON CONFLICT DO NOTHING in the handler's tx), KafkaConsumerConfig (3 retries 0.5/1/2 s → <topic>.DLT same partition; Invalid{Header,Payload} not retryable; DLT drops exception message/stacktrace headers — PII), DLT topics for both event topics, ack-mode record; shared app.kafka.partitions default
- notification: placeholder ReservationEventsListener (logs only; app.notification.listener.enabled, off in tests); payload missing reservationId/status → DLT without claim
- Spec r1 gap: no test for catalog-events.DLT → OutboxTopicsConfigTest. Accepted deviation: DLT any-partition fallback when source partition ≥ configured count
- Quality: 0 crit/high; M1 DLT exception headers could carry PII (fixed); L2 event-type log injection, L3 control chars, L4 empty payload poison, L5 duplicated partitions default (all fixed)
- Tests: 30 unit/arch + IdempotentConsumerIT 7/7 green

### [2026-11-25 12:45] [CI] — PR #13 GREEN (run 45596852098): api verify + SonarCloud, web, images + Trivy.

### [2026-11-25 12:45] [SHIP] — Story 3.1 outbox → Kafka with idempotent consumers
- Summary: feature/3.1-outbox pushed; PR #13 → main, CI green, Sonar gate OK. Coverage 99.1% lines.
- Status: complete

### [2026-11-25 13:00] [CI] — PR #13 GREEN on logs commit 79ea579 (run 45598237971). Entry uncommitted; goes in with the next branch.

### [2026-11-25 13:20] [UNDERSTAND] — Story 3.2 ES index + indexer + reindex
- Track: Standard (new ES adapter, Kafka consumer, admin endpoint, touches inventory events)
- Found: ES 8.19 in compose; ES_URL/ES_USER/ES_PASSWORD already in .env.example (no new env vars); search/ module empty; PLATFORM_ADMIN role in realm; index spec database §5
- Gaps: rate bulk updates emit no event (min price stale); RoomTypeChanged keyed by roomTypeId → one hotel's updates across partitions (out-of-order re-read/write)

### [2026-11-25 13:25] [BRAINSTORM] — Story 3.2 options presented (🟢 single index / 🟡 +HotelPricesChanged, hotel-keyed catalog events, alias-swap reindex / 🔴 +async job, external versioning); awaiting pick, recommended 🟡

### [2026-11-25 13:30] [CI] — main after PR #13 merge (bf55591), run 45599782727: GREEN (api verify + Sonar, web, images + Trivy + push).

### [2026-11-25 13:40] [PLAN] — Story 3.2 (🟡) — awaiting confirmation
- HANDOFF Orchestrator → Scrum Master: 🟡 chosen; need batches.
- B1 index foundation (ES client, Testcontainer, mapping, HotelSearchIndex alias ops) · B2 events (RoomTypeChanged keyed by hotel, HotelPricesChanged, HotelSnapshots/MinPrices APIs) · B3 indexer listener + reindex endpoint (advisory try-lock → 409) · B4 ITs, docs, VERIFY, ship
- Accepted limit: edits during a reindex may miss the new index until the next event

### [2026-11-25 13:50] [PLAN] — Story 3.2 confirmed by user ("go"); branch feature/3.2-search-index from main @ bf55591
### [2026-11-25 13:50] [EXECUTE] — 3.2 B1 dispatched to implementer (ES client, Testcontainer, mapping, HotelSearchIndex alias ops, non-fatal bootstrap)

### [2026-11-25 15:30] [EXECUTE] — 3.2 B1 implemented; spec PASS; quality FAIL r1
- Implementer: ES starter (client 9.4.8), JVM-wide ES 9.4.8 test container, strict hotels mapping (minPriceByMonth yyyy-MM → scaled_float), HotelDocument, HotelSearchIndex port + ES adapter (alias, is_write_index race-safe), non-fatal bootstrap; 331 unit + 254 IT green
- Quality r1: HIGH upsert without alias auto-creates concrete `hotels` index (permanent breakage) → requireAlias; MEDIUM non-atomic old-index delete → remove_index in same _aliases + deleteIndex port; LOWs: orphan WARN, bulk chunking 500, missing ITs, shared-container doc. Sent back to implementer.

### [2026-11-25 16:10] [EXECUTE] — 3.2 B1 done (quality r2 PASS)
- Fixes: upsert requireAlias(true); switchAliasTo add + remove_index in one _aliases; deleteIndex (ignoreUnavailable; refuses the live aliased index — orchestrator fix for r2 low); stray-index WARN + addSuppressed; bulk chunks 500, error cap 10; JVM-wide container convention documented
- Tests: HotelSearchIndexIT 18/18; search unit + Modularity + Hexagonal 16/16; NuzulApiApplicationTests 2/2
- Owner decisions applied: ES 9.4.8 (compose, CLAUDE.md, README, system design, devops); ES health indicator off

### [2026-11-25 16:20] [EXECUTE] — 3.2 B2 dispatched to implementer (RoomTypeChanged keyed by hotel, HotelPricesChanged in setRates tx, catalog HotelSnapshots find/activePage, inventory MinPrices.byMonth)

### [2026-11-25 17:20] [EXECUTE] — 3.2 B2 done (spec PASS, quality PASS)
- RoomTypeChanged keyed by hotel (ROOM_TYPE kept legacy-readable); HotelPricesChanged in setRates tx (hotelOfOwned owner-scoped, same 404); catalog HotelSnapshots find/activePage (keyset, 2 queries); inventory MinPrices.byMonth (one GROUP BY query)
- Quality fixes (orchestrator): M1 V6 index room_type(hotel_id); L2 comments: order not load-bearing (append-time vs commit-time); L4 date_trunc on ::timestamp (TZ-free); L5 removed duplicate existsOwned/isOwnedBy override
- Carry to B3: consumer takes hotelId from payload (legacy room_type-keyed rows), never compares occurredAt; MinPrices "today" from shared Clock (Africa/Casablanca)
- Tests: 335 unit + 141 IT (6 affected suites) green

### [2026-11-26] [EXECUTE] — 3.2 B3 dispatched to implementer (search-indexer listener, HotelDocument assembler, reindex endpoint with advisory try-lock + outbox catch-up)
### [2026-11-26] [EXECUTE] — 3.2 B3 implemented (mvnw verify: 364 unit + 284 IT green, JaCoCo 98.8% line); dispatched to spec-reviewer
- Deviations: ITs start search-indexer via KafkaListenerEndpointRegistry + drive relay by hand (no new context); catch-up IT weak by nature, logic proven in ReindexServiceTest
- Noticed: SecurityConfig comment promises a reindex request matcher that doesn't exist; ES container 60 s startup wait borderline (2 flaky runs)
### [2026-11-26] [EXECUTE] — 3.2 B3 spec PASS (notes: catch-up IT could use a divergent doc; unknown-type w/o event-id → DLT); dispatched to quality-reviewer
### [2026-11-26] [EXECUTE] — 3.2 B3 quality FAIL r1
- HIGH: one JPA tx around reindex → catch-up find() returns the persistence-context copy loaded during paging (stale name/status written to ES; also memory growth + dirty-checking). Fix: lock on its own tx/connection, catalog reads in their own short tx; IT editing a hotel mid-reindex.
- MEDIUM: ES outage > ~3.5 s dead-letters catalog events (no replay/alert) → raised with owner; MEDIUM: unbounded sync request/long tx (resolved by HIGH fix)
- LOWs sent back too: catch-up IT premise, start from DB clock, SecurityConfig matcher, ES container startup timeout 3 min
### [2026-11-26] [EXECUTE] — 3.2 B3 r1 fixes done (mvnw verify: 380 unit + 287 IT green, JaCoCo 98.2% line); quality r2 dispatched
- Lock: session pg_try_advisory_lock on a dedicated DataSource connection (runExclusively), reads in their own short txs; start from DB clock_timestamp(); SecurityConfig PLATFORM_ADMIN matcher; ES startup 3 min
- New IT catchUpAppliesEditsMadeAfterThePagesWereRead fails with old @Transactional (proven), passes now
- ES outage: SearchUnavailableException classified in adapter; search-indexer factory with unbounded 1s→30s backoff; shared ListenerErrorHandlers
### [2026-11-26] [EXECUTE] — 3.2 B3 done (quality r2 PASS)
- Open mediums: M1 alias missing (ES cold start beats one-shot bootstrap) → upsert/delete 404 → DLT; M2 permanent 5xx stalls partition silently (accepted trade-off, needs WARN/lag alert). Lows: PG outage → DLT, session lock vs idle_session_timeout/PgBouncer, orphan hotels-<ts> indices, abort-path/exception-change tests, SwapHook thread-local.
### [2026-11-26] [EXECUTE] — 3.2 B3 committed fc59d71; B4 code fixes dispatched to implementer, docs inline
### [2026-11-26] [EXECUTE] — 3.2 B4 docs: database §5 as-built + V6, ADR-7 (alias swap + outbox catch-up), security role/route table, test strategy §Search index
### [2026-11-26] [EXECUTE] — 3.2 B4 code fixes implemented (mvnw verify: 393 unit + 289 IT green, JaCoCo 98.4% line / 92.7% branch); spec review dispatched
### [2026-11-26] [EXECUTE] — 3.2 B4 code spec PASS (note: setRetryListeners replaces — spring-kafka 4.1.2 has no addRetryListener; fine while shared handler registers none); quality review dispatched
### [2026-11-26 11:25] [CI] — PR #14 opened (feature/3.2-search-index @ bc8f76b); watching checks
### [2026-11-26 11:35] [CI] — PR #14 run 45809538789 RED: HotelDocumentAssemblerTest (CI JDK tzdata resolves Africa/Casablanca to UTC+0 → "today" Nov 30 not Dec 1; same class of issue as story 1.x). Fix: fixed ZoneOffset.ofHours(1) in the test; test green locally. ITs not reached in that run.
