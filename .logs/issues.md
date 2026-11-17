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
