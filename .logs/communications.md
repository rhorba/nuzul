# Communications Log
<!-- Tracks key questions, user preferences, handoffs -->
<!-- Format: ### [YYYY-MM-DD HH:MM] PREFERENCE/HANDOFF/QUESTION — Title -->


### [2026-11-17] [UNDERSTAND] — Sprint 0
- Owner: "lets cook" → continue with Sprint 0 per docs/stories-nuzul.md.
- Track: 🔴 Full (new project, first code, CI + infra).
- Env vars: already in .env.example (local compose defaults); prod values deferred to Sprint 6. Only new need: SONAR_TOKEN (GitHub secret) at 0.3.
- Toolchain: JDK 25 local (compile with --release 21), Node 22, Docker 27 + compose v2, gh CLI.
