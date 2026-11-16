# Decision Log
<!-- Tracks architecture decisions, approach selections, tool choices -->
<!-- Format: ### [YYYY-MM-DD HH:MM] ARCHITECTURE/APPROACH/TOOL — Title -->


### [2026-11-16] [BRAINSTORM] Scope and stack
Options: 🟢 compose-only · 🟡 Balanced (monolith + Helm/k3s + Terraform S3/CloudFront + SonarCloud + OTel) · 🔴 microservices on EKS.
**Owner picked 🟡 Balanced.** Chat UI = React + TS. Names: nuzul (hotel), hdra (chat).
Rationale: market data — Angular 35/53, React 21/53, gaps MongoDB 13, AWS 12, Vue 12, SonarQube 9, ES 5–8, Helm 8, WebSocket 6.
