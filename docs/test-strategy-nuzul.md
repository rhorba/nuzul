# Test Strategy: Nuzul

Owner: Test Architect · Status: DRAFT

## Pyramid
| Layer | Tool | Scope |
|---|---|---|
| Unit | JUnit 5, AssertJ | Domain: price calculation, the hold state machine, the cancellation policy (pure Java, no Spring) |
| Architecture | Spring Modulith `verify()`, ArchUnit | Module boundaries; the domain does not import Spring |
| Integration | Testcontainers (Postgres, Redis, ES, Kafka) | Repositories, the guard SQL, the outbox → Kafka path, ES queries, DB integrity tests |
| API | MockMvc + Spring Security test JWTs | Controllers, `problem+json` codes, IDOR and cross-hotel tests |
| Frontend unit | Vitest (Angular) | Services, the countdown, problem mapping |
| E2E | Playwright against a prod build + compose stack | Search → hold → confirm → email in Mailpit; sold out; hold expired; admin bulk edit |
| Load | k6 | Spike: 500 VUs on one room type with 10 rooms, expecting exactly 10 confirmed and the rest 409 |

## Release-gate tests (named, must exist before Sprint 2 closes)
1. `LastRoomConcurrencyIT`: 200 threads, 1 room left, exactly 1 `HELD`, 199 `SOLD_OUT`, and `reserved == total` afterwards.
2. `MultiNightAtomicityIT`: night 2 of 3 is full, so the hold fails and nights 1 and 3 are unchanged.
3. `IdempotencyIT`: the same key sent 5× in parallel gives 1 reservation and 5 identical responses; it also holds with Redis stopped.
4. `HoldExpiryIT`: an expired hold releases inventory exactly once, even with two schedulers racing.
5. `InventoryIntegrityIT`: the DB refuses oversell even through a raw SQL write.

## Coverage
JaCoCo ≥ 80 % lines on `api` (CTS rule 6), enforced by the Sonar quality gate, which also requires 0 new bugs or vulnerabilities and ≤ 3 % duplication.
