# Security Baseline: Nuzul

Owner: Security Engineer · Status: DRAFT

## 1. Threat model (5 minutes)
Assets: guest PII (name, email), reservation integrity, hotel-admin control of inventory and prices.
Attackers: anonymous scrapers, a guest tampering with other guests' bookings, a hotel admin editing another hotel.

## 2. STRIDE (top risks)
| Threat | Example | Control |
|---|---|---|
| Spoofing | Forged JWT | Keycloak RS256; the API checks `iss`, `aud` and expiry; JWKS cached |
| Tampering | Changing the price in the request | The price is never accepted from the client; computed server-side from `rate` |
| Repudiation | "I never cancelled" | An audit log of reservation state changes with `sub`, IP and traceId |
| Info disclosure | IDOR on `/reservations/{id}` | Ownership check in the query (`AND guest_sub = :sub`); 404, not 403 |
| DoS | Hold-spamming to block inventory | At most 5 active holds per guest, enforced exactly in the hold transaction (per-guest advisory lock + count) → 429 `TOO_MANY_ACTIVE_HOLDS`; 30 POST/min per guest (Bucket4j on Redis, keyed by JWT `sub`) → 429 `RATE_LIMITED` + `Retry-After`; the limiter fails open if Redis is down, so the cap and the 10-min hold expiry remain the backstop |
| Elevation | A hotel admin edits another hotel | `hotel.owner_sub = jwt.sub` in every admin query; tested |

## 3. Authentication
Keycloak 26 realm `nuzul`. The SPA uses the code flow with PKCE, with tokens in memory (no localStorage). Access tokens live 5 min, refresh tokens rotate.
Roles: `GUEST`, `HOTEL_ADMIN`, `PLATFORM_ADMIN`.

## 4. Authorisation
Role checks with `@PreAuthorize` at the controller, plus **data scope in SQL** at the repository. A role claim is never enough on its own.

| Role | Routes | Scope |
|---|---|---|
| `GUEST` | `/api/v1/reservations/**` | own reservations (`guest_sub = jwt.sub`) |
| `HOTEL_ADMIN` | `/api/v1/admin/hotels/**`, `/api/v1/admin/room-types/**` (hotels, room types, inventory, rates) | own hotels (`hotel.owner_sub = jwt.sub`), others 404 |
| `PLATFORM_ADMIN` | `/api/v1/admin/search/**` (reindex, story 3.2) | platform-wide; no data returned beyond index name and counts |

Admin routes are guarded twice: a request matcher in `SecurityConfig` and `@PreAuthorize` on the controller; the two must agree.

## 5. Data protection
TLS everywhere (Traefik + Let's Encrypt). PII stays in Postgres only, never in ES or logs (Logback masking for email).
S3 bucket is private; reads go through CloudFront with OAC, writes through 5-minute presigned PUTs limited to `image/jpeg|png|webp` and 5 MB.

## 6. Requirements for the dev team
- Validate all input with Bean Validation; errors as `problem+json` with no stack traces.
- CORS allows only the SPA origin. Headers: CSP, HSTS (when proxied over https), X-Content-Type-Options.
- Dependencies: Dependabot plus Trivy (image and fs) in CI; fail on CRITICAL.
- Secrets live only in k8s Secrets and GitHub Actions secrets; `.env.example` holds placeholders.
- Tests: an IDOR test per resource, a cross-hotel admin test, a rate-limit test.
