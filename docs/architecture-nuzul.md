# Architecture: Nuzul

Owner: Software Architect · Status: DRAFT

## 1. Overview
A monorepo with two deployables: `api/` (Spring Boot 4.1, Java 21, Maven wrapper) and `web/` (Angular SPA served
by nginx). The infrastructure lives in `deploy/helm/nuzul` and `infra/terraform/aws`. The runtime picture is in
`system-design-nuzul.md`, Step 3.

## 2. Architecture decision records
### ADR-1: Modular monolith with Spring Modulith
**Context:** one developer; the hold and its outbox event must share a DB transaction.
**Decision:** one Spring Boot app with modules `catalog`, `inventory`, `reservation`, `payment`, `search`,
`notification` and `shared`. `ApplicationModules.verify()` runs in a test, so a module that reaches into another's internals fails the build.
**Consequence:** a later split into services follows the module lines. Spring Modulith also gives the event publication registry.

### ADR-2: Hexagonal inside each module
`domain` (pure Java, no Spring) → `application` (use cases, ports) → `adapter.in.web` and `adapter.out.persistence/es/kafka/s3`.
The pattern is the same as LawFirm, so the owner can defend it in an interview.

### ADR-3: Persistence through Spring Data JPA, plus native SQL for the guard
JPA for CRUD. The inventory guard is a `@Modifying @Query(nativeQuery = true)` statement, because the predicate under the row lock *is* the design.
Flyway owns the schema; `ddl-auto=validate`.

### ADR-4: Outbox → Kafka via a scheduled poller
An `outbox_event` row is written in the business transaction, and a `@Scheduled` poller publishes and marks it with `FOR UPDATE SKIP LOCKED`.
Consumers are idempotent: they record each processed event id.

### ADR-5: Auth through Keycloak; the API is a resource server
The Angular app uses the OIDC code flow with PKCE (angular-auth-oidc-client). The API validates JWTs and maps realm roles to authorities.
Hotel-admin scope is checked in the DB (`hotel.owner_sub = jwt.sub`), never from a claim alone.

### ADR-6: Angular with standalone components and signals; PrimeNG for components
PrimeNG appears in Moroccan ESN stacks and matches the owner's PrimeFaces history. No NgRx: signals and services are enough (YAGNI).

## 3. Package layout (api)
```
ma.nuzul
 ├─ catalog/{domain,application,adapter}
 ├─ inventory/…   reservation/…   payment/…   search/…   notification/…
 └─ shared/{web/ProblemDetails, outbox, idempotency, security}
```

## 4. Data model
See `database-nuzul.md`.

## 5. API design
See `system-design-nuzul.md`, Step 2. springdoc serves `/v3/api-docs`, and the Angular client types are generated from it with openapi-typescript.

## 6. Security
See `security-nuzul.md`.

## 7. Infrastructure
Local: `docker compose` with Postgres, Redis, ES, Kafka (KRaft), Keycloak, Mailpit and MinIO (the S3 stand-in).
Prod: k3s with a Helm chart; managed AWS S3 + CloudFront. See `devops-nuzul.md`.

## 8. Technical risks
| Risk | Mitigation |
|---|---|
| Testcontainers with ES + Kafka slow down CI | Singleton containers per JVM; test slices for unit layers |
| Keycloak config drift | Realm exported to `deploy/keycloak/realm-nuzul.json`, imported on start |
