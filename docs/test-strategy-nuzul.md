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

## Security tests (story 2.5, security doc §6)
**IDOR / cross-owner, one per resource.** Another owner or guest always gets 404 (never 403), and nothing changes.

| Resource | Endpoint(s) | Test |
|---|---|---|
| Hotel | `GET/PUT /api/v1/admin/hotels/{id}` | `HotelAdminIT.otherOwnerGetsNotFoundOnGetAndPutAndHotelIsUnchanged` |
| Hotel list | `GET /api/v1/admin/hotels` | `HotelAdminIT.listReturnsOnlyCallersHotelsNewestFirstWithPageMetadata` |
| Room type | `/api/v1/admin/hotels/{hotelId}/room-types/**` | `RoomTypeAdminIT.otherOwnerGetsHotelNotFoundOnEveryEndpointAndNothingChanges`, `updateUnderTheWrongHotelIsRoomTypeNotFoundAndLeavesItUnchanged` |
| Inventory | `PUT /api/v1/admin/room-types/{id}/inventory` | `InventoryBulkIT.otherOwnerIs404RoomTypeNotFoundAndNothingChanges` |
| Rates | `PUT /api/v1/admin/room-types/{id}/rates` | `RateBulkIT.otherOwnerIs404RoomTypeNotFoundAndNothingChanges` |
| Reservation confirm | `POST /api/v1/reservations/{id}/confirm` | `ConfirmIT.anotherGuestsReservationIs404AndACancelledOneIs409` |
| Reservation cancel | `POST /api/v1/reservations/{id}/cancel` | `CancelIT.expiredIs410AndAnotherGuestsIs404` |
| Idempotency key | `POST /api/v1/reservations` | `IdempotencyIT.theSameKeyFromAnotherGuestIsAnotherReservation` |

A new resource is not done until it has a row here.

**Abuse limits.**
- `RateLimitIT`: per-guest bucket on reservation POSTs gives 429 `RATE_LIMITED` with `Retry-After`. Buckets are per guest, 401/403 don't consume tokens, and the limiter fails open when Redis is down.
- `ActiveHoldCapIT`: the 6th active hold gets 429 `TOO_MANY_ACTIVE_HOLDS`. Expired and cancelled holds don't count, and replays are never rejected. 10 parallel holds against 4 active ones give exactly 1 success.

## Event delivery (story 3.1)
- `OutboxEventsIT`: hold, cancel and expiry each write exactly one event in their own transaction, with no personal data. A replayed key writes none, and a sold-out hold rolls its event back with the reservation.
- `OutboxRelayIT`: records carry their key and headers, and rows are marked published. One aggregate's events arrive in order. A record that is too large is parked without blocking the rows behind it. Two concurrent relays publish every event exactly once. Also covers the lag/parked gauges and retention.
- `IdempotentConsumerIT`: a duplicate is handled once. Transient failures are retried, then the record is dead-lettered with its original value and headers (no exception message or stack trace) and no claim row. A missing event id or a tombstone goes to the DLT with no retry. A held reservation reaches the notification consumer end to end through the relay.

## Search index (story 3.2)
- `HotelSearchIndexIT`: alias bootstrap is race-safe; upserts need the alias and recreate it when missing; the swap is atomic and removes old and orphaned indices; bulk chunking and rejected documents.
- `SearchIndexerIT`: hotel create/edit and a rate change flow through the relay and the `search-indexer` to the document (exact min price per month); retiring the hotel removes it.
- `ReindexIT`: PLATFORM_ADMIN only (401/403); the new index holds active hotels only and the alias moves; a held lock gives 409 `REINDEX_IN_PROGRESS` and creates nothing; a hotel renamed and another retired *after* their page was read are correct after the catch-up (fails if the reindex runs in one transaction).
- `JdbcReindexLockIT`, `JdbcRecentCatalogChangesIT`: lock held only during the work and released after a failure; the catch-up query's window and filtering.
- Unit: listener branches (payload hotelId, unknown type skipped, poison → DLT before claim); retry classification (ES outage and transient DB errors retried without limit, everything else bounded → DLT).

## Coverage
JaCoCo ≥ 80 % lines on `api` (CTS rule 6), enforced by the Sonar quality gate, which also requires 0 new bugs or vulnerabilities and ≤ 3 % duplication.
