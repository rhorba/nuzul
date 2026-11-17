# Decision Log
<!-- Tracks architecture decisions, approach selections, tool choices -->
<!-- Format: ### [YYYY-MM-DD HH:MM] ARCHITECTURE/APPROACH/TOOL — Title -->


### [2026-11-16] [BRAINSTORM] Scope and stack
Options: 🟢 compose-only · 🟡 Balanced (monolith + Helm/k3s + Terraform S3/CloudFront + SonarCloud + OTel) · 🔴 microservices on EKS.
**Owner picked 🟡 Balanced.** Chat UI = React + TS. Names: nuzul (hotel), hdra (chat).
Rationale: market data — Angular 35/53, React 21/53, gaps MongoDB 13, AWS 12, Vue 12, SonarQube 9, ES 5–8, Helm 8, WebSocket 6.

### [2026-11-17] [BRAINSTORM] — Sprint 0 scope
- Chosen: Full Sprint 0 (0.1→0.4) this session; latest stable versions (Boot 3.5.x, Modulith 1.4.x, Angular + PrimeNG latest).
- Rejected: skeletons-only (leaves CI red-risk to next session); Sprint 0 + 1.1 (too long for one checkpointed session).

### [2026-11-17] [EXECUTE] — Spring Boot 4.1.2 instead of 3.x
- Initializr no longer offers 3.x; 3.5 OSS support ended mid-2026. Owner chose 4.1.2. Docs + CLAUDE.md updated.
