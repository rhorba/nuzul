# Risk Log
<!-- Tracks risks identified and their mitigations -->
<!-- Format: ### [YYYY-MM-DD HH:MM] SECURITY/PERFORMANCE/DEPENDENCY — Title -->


### [2026-11-18] [RISK] — pg_partman on CloudNativePG (k3s)
- Standard CNPG operand images don't ship pg_partman. Helm sprint must build a CNPG-compatible image (FROM ghcr.io/cloudnative-pg/postgresql:16.x + postgresql-16-partman) and set shared_preload_libraries=pg_partman_bgw.
- Impact: medium (deploy blocker if missed). Owner: DevOps, Helm sprint.

### [2026-11-26] [RISK] — search-indexer partition stall on persistent ES 5xx (owner-accepted trade-off)
- Signal today: consumer lag on search-indexer only. Mitigation: WARN per outage retry + lag alert (observability backlog); runbook: fix cluster or reset offsets + reindex.
