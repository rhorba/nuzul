# Session Log
<!-- Tracks session starts and ends for resumption -->
<!-- Format: ### [YYYY-MM-DD HH:MM] SESSION_START/SESSION_END -->


### [2026-11-16] Session 1 — project inception
**Context:** Owner asked for a hotel reservation app then a chat app, built with CTS, following Hayk Simonyan's
five-step system-design framework, using the stack of on-site/hybrid offers to fill CV gaps.
**Done:** LinkedIn scan (105 offers, 92 read, 53 Java) → gap file `../find a remote freelance job/market-gap-2026-11-16.md`.
BRAINSTORM gate answered: Balanced scope; chat frontend React; repos rhorba/nuzul + rhorba/hdra.
All 10 foundation docs drafted in `docs/` (CTS rule 13).
**Next:** owner approves docs → commit + push docs → Sprint 0. Then the same doc pass for hdra.

### [2026-11-16] SESSION_END
**Done:** owner approved the foundation docs ("ok do it"); repo rhorba/nuzul created (public) and docs pushed.
**Next (open Claude Code in this folder):** Sprint 0 per docs/stories-nuzul.md — 0.1 skeletons (Maven wrapper,
Spring Boot 3 + Modulith, Angular + PrimeNG), 0.2 compose.yaml, 0.3 CI + SonarCloud + Trivy, 0.4 measure RAM of the stack.
Needs from owner at Sprint 0.3: a SonarCloud account linked to GitHub (SONAR_TOKEN secret).

### [2026-11-17] SESSION_START — Session 2
**Resume from:** docs approved + pushed (2529245). Starting Sprint 0 (foundation code).

### [2026-11-17] SESSION_END
**Done:** Sprint 0 complete — api (Boot 4.1.2 + Modulith, 7 tests, 83% cov), web (Angular 22 + PrimeNG), compose (PG:5435, Redis, ES, Kafka, Keycloak realm, Mailpit),
CI (Sonar-ready, Trivy, GHCR multi-arch), README, RAM measured (≈3.1 GB idle; k3s plan ≈6 GB). PR #1 → main.
CTS plugin installed; fixed push-gate bug (rhorba/CTS branch fix/push-gate-command-filter, e7c1f8f, local only).
**Open for owner:** set SONAR_TOKEN secret; decide whether to push/PR the CTS hook fix.
**Next:** Sprint 1 — 1.1 Flyway V1 schema + InventoryIntegrityIT (docs/database-nuzul.md §3 is binding), then 1.2–1.5.

### [2026-11-18] SESSION_START
- Resumed from 2026-11-17 SESSION_END. Owner chose: continue Sprint 1, story 1.1.

### [2026-11-18] SESSION_END
**Done:** Story 1.1. V1 schema with pg_partman (premake 18, DEFAULT partition), custom image deploy/postgres (Debian gosu replaces the CVE-flagged upstream binary), Failsafe + merged JaCoCo (83.3%), InventoryIntegrityIT (10 tests). Spec and quality reviews PASS. PR #2, CI green.
**Open for owner:** merge PR #2; run `docker compose down -v` once locally (Alpine→Debian volume); SONAR_TOKEN still unset.
**Next:** story 1.2 Hotel/room-type CRUD (owner-scoped) + cross-hotel test. Remember story 1.3 must refuse nights beyond the 180-day window (keeps the DEFAULT partition empty).
