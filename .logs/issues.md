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
