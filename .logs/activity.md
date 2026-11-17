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
