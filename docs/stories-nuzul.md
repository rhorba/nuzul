# Stories: Nuzul

Owner: Scrum Master · Status: DRAFT · each sprint ≈ 2–3 sessions, closed with VERIFY + SHIP + push (CTS rule 7)

## Sprint 0 — Foundation (code begins only after these docs are approved)
- 0.1 Repo, Maven wrapper, Spring Boot 4 skeleton with Modulith; Angular skeleton; Biome/ESLint; Spotless.
- 0.2 `compose.yaml`: PG, Redis, ES, Kafka, Keycloak (realm import), Mailpit, MinIO.
- 0.3 CI: build + test + SonarCloud + Trivy; README with an architecture diagram.
- 0.4 Measure the stack's RAM in compose → confirm the k3s budget (devops doc).

## Sprint 1 — Catalog + inventory guard
- 1.1 Flyway V1 schema + `InventoryIntegrityIT`.
- 1.2 Hotel and room-type CRUD (admin, scoped to owner) + cross-hotel test.
- 1.3 Bulk inventory and rate endpoints (refuse `total < reserved`).
- 1.4 `InventoryRepository.reserve()` (the guard) + `LastRoomConcurrencyIT` + `MultiNightAtomicityIT`.
- 1.5 Seed: 6 cities (Marrakech, Fès, Chefchaouen, Essaouira, Agadir, Tanger), ~60 hotels, 180 days of inventory.

## Sprint 2 — Reservations
- 2.1 Hold (`POST /reservations`) with an Idempotency-Key (Redis + unique constraint) + `IdempotencyIT`.
- 2.2 Confirm via `PaymentPort` / `FakePaymentAdapter` (a card ending 0002 fails).
- 2.3 Cancel with the free-cancellation rule.
- 2.4 Expiry job (ShedLock, SKIP LOCKED) + `HoldExpiryIT`.
- 2.5 Rate limiting (Bucket4j/Redis) + IDOR tests.

## Sprint 3 — Search and events
- 3.1 Outbox table + poller → Kafka; idempotent consumers.
- 3.2 ES index + indexer consumer + reindex endpoint.
- 3.3 `GET /search`: ES query + batched availability check + Resilience4j fallback to PG.
- 3.4 Notifier: confirmation/cancellation email (Thymeleaf template) via Mailpit.

## Sprint 4 — Angular guest app
- 4.1 Shell, tokens, PrimeNG preset, i18n FR/EN, OIDC login.
- 4.2 Search + results + filters. 4.3 Hotel page. 4.4 Hold + countdown + confirm. 4.5 My reservations + cancel.
- 4.6 Playwright: book, sold out, expired.

## Sprint 5 — Admin + photos
- 5.1 Admin hotels and room types. 5.2 Availability grid with bulk edit. 5.3 S3 presigned upload (MinIO locally) + CloudFront URLs.

## Sprint 6 — Production and v1.0
- 6.1 Helm chart + values-prod; Argo CD app. 6.2 Terraform: S3 + CloudFront + IAM. 6.3 OTel agent, Prometheus metrics,
  Grafana dashboard JSON, Loki. 6.4 k6 spike test report in `docs/`. 6.5 Playwright video (CTS rule 9), tag v1.0.0.
