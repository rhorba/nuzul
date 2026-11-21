# Issue Log
<!-- Tracks bugs, errors, blockers with status -->
<!-- Format: ### [YYYY-MM-DD HH:MM] BUG/BLOCKER/ERROR — Title -->


### [2026-11-17] BUG — CTS push-gate blocked every Bash command
- Cause: push-gate.sh never inspected the command; hooks.json `if: Bash(git push*)` let compound commands through.
- Fix: rhorba/CTS branch fix/push-gate-command-filter (e7c1f8f, local, not pushed) + 2 tests; owner copied fixed script into installed plugin.
- Status: resolved locally; PR to CTS pending owner OK.

### [2026-11-17 12:40] [VERIFY] — Sprint 0 foundation (api + web skeletons, compose, CI)
- Tests: 9 passed / 0 failed (api 7: Modulith, ArchUnit ×3, Testcontainers smoke ×2; web 2 Vitest)
- Coverage: api 83.3% lines (JaCoCo gate ≥80% met), web 100% lines
- Security: pass — Trivy images 0 fixable CRITICAL (web brotli fixed via apk upgrade); Trivy fs secret+misconfig 0 HIGH/CRITICAL; npm audit prod 0
- Lint: Spotless check + angular-eslint clean
- Status: pass

### [2026-11-17 12:50] [VERIFY] — RETROACTIVE: session-1 docs push (2026-11-16, logged before the CTS plugin existed)
- Tests: n/a — documentation only (10 foundation docs, no code)
- Verification: owner reviewed and approved all foundation docs ("ok do it", .logs/sessions.md 2026-11-16 SESSION_END)
- Coverage: n/a — documentation only
- Security: pass — no code, no secrets in docs
- Status: pass
- Note: recorded retroactively with owner approval (2026-11-17) so the push gate's VERIFY/SHIP count reflects history.

### [2026-11-18 10:30] [VERIFY] — Story 1.1 Flyway V1 + pg_partman + InventoryIntegrityIT
- Tests: 17 passed / 0 failed (surefire 7, failsafe 10)
- Coverage: 83.3% lines (JaCoCo merged unit+IT)
- Reviews: spec PASS; quality PASS (2 medium + 5 low, all addressed)
- Security: issues found — Trivy CRITICAL CVE-2025-68121 (Go stdlib crypto/tls, fixed in 1.24.13) in usr/local/bin/gosu of upstream postgres:16.16-bookworm; would fail CI images gate. Plus 25 HIGH in same binary.
- Status: fail

### [2026-11-18 10:45] [VERIFY] — Story 1.1 re-verify after gosu fix
- Tests: 17 passed / 0 failed (surefire 7, failsafe 10 incl. bgw-loaded check)
- Coverage: 83.3% lines (JaCoCo merged unit+IT)
- Security: pass — Trivy CRITICAL/HIGH 0 on nuzul-postgres (Debian gosu 1.14 replaces upstream Go 1.24.6 build; Debian tracker rates gosu stdlib CVEs not exploitable). Container PID 1 uid 999 (postgres).
- Reviews: spec PASS, quality PASS (findings addressed)
- Status: pass

### [2026-11-19 11:50] [VERIFY] — Story 1.2 hotel + room-type admin CRUD
- Tests: 58 unit + 71 IT passed / 0 failed (./mvnw verify: Spotless, ArchUnit hexagonal, Modulith ModularityTest, Failsafe, JaCoCo check)
- Security: pass — cross-owner/cross-hotel 404 tests with DB snapshots, owner never from body (mass-assignment test), GUEST 403 / no token 401 on every endpoint, realm-role mapping proven through real bearer filter chain (mutation-checked), input bounds (@Size/@Digits/@Max), no generic exception handler, no detail leak on 400s; no new dependencies; no secrets in diff.
- Reviews: spec PASS ×2, quality PASS ×2 (0 critical/high open)
- Status: pass

### [2026-11-19 12:15] [VERIFY] — CI fix: SonarCloud gate (java:S4502 CSRF) on PR #3
- Cause: CI run 44501525712 red; quality gate new_security_rating=4 from one issue, S4502 on csrf.disable() (pre-existing line counted as new after signature change). Tests/coverage in CI were fine (new_coverage 94.9%).
- Fix (owner chose A): @SuppressWarnings("java:S4502") + javadoc justification (stateless, Bearer header only, no auth cookies).
- Tests: 58 unit + 71 IT passed / 0 failed (local ./mvnw verify)
- Security: pass — CSRF not applicable to bearer-only stateless API; decision recorded in code.
- Status: pass

### [2026-11-19 14:45] [VERIFY] — Story 1.3 bulk inventory + rates
- Tests: 112 unit + 139 IT passed / 0 failed (./mvnw verify incl. Spotless, ArchUnit, Modulith, JaCoCo)
- Security: pass — ownership via catalog.RoomTypeOwnership before any write (404 cross-owner, snapshot-asserted); URL + method role layers (GUEST invalid body → 403, mutation-checked); upsert re-checks latest reserved under row lock, all-or-nothing 409; window keeps pg_partman DEFAULT empty; BigDecimal money; no new deps; no secrets.
- Reviews: spec PASS ×2, quality PASS ×2 (0 critical/high open)
- Status: pass

### [2026-11-19 15:00] [VERIFY] — CI fix: tzdata-dependent unit tests on PR #4
- Cause: CI run 44515874289 red; InventoryAdminServiceTest/RateAdminServiceTest fixed clock 2026-11-18T23:30Z in Africa/Casablanca → Nov 19 locally (Temurin 25.0.3, tzdata 2026a, +01:00) but Nov 18 on CI (Temurin 21.0.13, newer tzdata). Production code unaffected (follows JDK tzdata).
- Fix: unit tests use fixed ZoneOffset +01:00 (still proves "today" comes from the clock zone); ITs already pinned to local noon.
- Tests: 112 unit passed / 0 failed locally; full IT suite unchanged since last pass.
- Security: n/a (test-only change)
- Status: pass

### [2026-11-19 19:35] [VERIFY] — Story 1.4 reserve() guard + release gates 1–2
- Tests: 139 unit + 158 IT passed / 0 failed (orchestrator's independent ./mvnw verify; concurrency ITs 3× green by implementer, no flakiness)
- Security: pass — guard cannot oversell (predicate on locked values + CHECK backstop untouched), MANDATORY tx so it can't run outside the caller's transaction, no swallowed 23514, no new endpoints/deps, no secrets.
- Reviews: spec PASS; quality PASS round 2 (round 1 findings: false SOLD_OUT + pruning → fixed; blocker resolved by owner decision A)
- Status: pass

### [2026-11-20 21:40] [VERIFY] — Story 1.5 seed data
- Tests: 148 unit + 161 IT passed / 0 failed (./mvnw verify, Testcontainers PG16); DemoSeederTest re-run after order-test hardening 2/2.
- Security: pass — quality-reviewer PASS; stockedNights owner-checked (NOT_FOUND for others), not exposed over HTTP, parameterised SQL; seed default off; owner sub is a public realm id, no secrets.
- Review fixes: run() logs instead of aborting startup (midnight rollover); single-instance seeding documented (advisory lock carried to 6.1 Helm); rates-before-totals order test; default-off context test.
- Status: pass

### [2026-11-20 21:58] [VERIFY] — Story 1.5 Sonar S8696 fix
- Tests: SeedCatalogTest 5/5 + DemoSeederTest 2/2 pass (priceOn weekend/weekday cases covered); no other code changed since full verify (148 unit + 161 IT).
- Security: pass (no change in surface)
- Status: pass

### [2026-11-20 23:30] [VERIFY] — Story 2.1 Hold + idempotency
- Tests: 174 unit + 180 IT passed / 0 failed (./mvnw verify, Testcontainers PG16 + Redis 7.4). Gates: LastRoomConcurrencyIT (hold path) 2/2, IdempotencyIT 3/3, IdempotencyRedisDownIT 3/3, plus MultiNightAtomicityIT unchanged.
- Security: pass — quality-reviewer PASS (no critical/high). Guest only from JWT; key lookup filtered by guest_sub; Redis key unambiguous; logs carry no PII; GUEST role at filter + @PreAuthorize.
- Review fixes: null-safe hold_expires_at mapping; total > NUMERIC(12,2) → 400; Javadoc placement; readOnly note. Deferred: hanging-Redis (timeout) test.
- Status: pass

### [2026-11-21 14:30] [VERIFY] — Story 2.2 Confirm via PaymentPort
- Tests: 199 unit + 192 IT passed / 0 failed (./mvnw verify). ConfirmIT 7/7 (5x parallel → 1 capture, one provider idempotency key; expiry during charge → REFUNDED + 410), PaymentIT 5/5, ConfirmServiceTest 12, PaymentServiceTest 10.
- Security: pass — quality-reviewer: initial HIGH (refund vs capture race → confirmed + refunded) FIXED and re-reviewed PASS (claim PENDING→REFUNDING before provider call; capture boolean + tx2 rollback; now inside tx2). Medium (provider error after claim) FIXED with REFUNDING state. IDOR blocked (guest-scoped load), token never logged/persisted.
- Also fixed: Redis health contributor disabled (fail-open cache must not turn /actuator/health DOWN; seen as flaky 503 in NuzulApiApplicationTests).
- Open low: twin that loses capture to a refund claim answers 410 though its own hold may be valid (money consistent).
- Status: pass

### [2026-11-21 18:00] [VERIFY] — Story 2.3 Cancel with free-cancellation rule
- Tests: 220 unit + 205 IT passed / 0 failed (./mvnw verify). CancelIT 9/9 (incl. cancel during confirm's charge, 5x parallel = one release + one distinct refund key), InventoryReleaseIT 4/4 (40 concurrent reserve/release, no deadlock), CancellationPolicyTest 6, CancelServiceTest 9.
- Security: pass — quality-reviewer initial FAIL (HIGH duplicate provider refund calls) fixed via required refund idempotency key in the PaymentPort contract; re-review PASS. IDOR blocked (guest-scoped load), lock order reservation → inventory (ascending) → payment verified deadlock-free.
- Open low: CancelIT static REFUND_KEYS assumes sequential test methods (default). Carry: Stripe adapter must send the refund key as Idempotency-Key; 2.4 expiry one reservation per tx.
- Status: pass
