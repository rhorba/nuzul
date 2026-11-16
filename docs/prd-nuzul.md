# PRD: Nuzul — hotel reservation platform

Owner: PM · Status: DRAFT

## 1. Problem statement
This is a portfolio product with a hiring purpose. On-site and hybrid Java offers in Morocco (53 read on
2026-11-16, see `../find a remote freelance job/market-gap-2026-11-16.md`) ask for Spring Boot + Angular +
Docker/Kubernetes + CI, and increasingly MongoDB, **Elasticsearch, Redis, SonarQube, AWS and Helm**. The owner's
CV covers the first group but no project shows the second. Nuzul is a Booking.com-style app whose hard problem
(no double booking under concurrency) is a strong interview story and exercises every one of those tools.

## 2. Goals and success metrics
| Goal | Metric |
|---|---|
| Interview-ready design story | The system-design doc is presentable in 15 minutes and every claim is backed by a test |
| No oversell | A 200-thread test on the last room yields exactly 1 success, in CI on every push |
| Gap tools shown in real use | ES search, Redis idempotency and cache, Sonar gate, Helm chart, S3 + CloudFront via Terraform, all live |
| Quality | ≥ 80 % coverage (JaCoCo), Sonar quality gate green, 0 critical Trivy findings |
| Live demo | Public URL on k3s with seeded data (6 Moroccan cities, ~60 hotels) |

## 3. Users
- **Guest**: searches, holds, pays, cancels.
- **Hotel admin**: manages own hotels, room types, inventory, rates and photos.
- **Platform admin**: approves hotels (v1: a seeded role only).

## 4. Scope
**In:** functional items 1–8 of the system design, Step 1. FR/EN UI (FR default; AR later).
**Out:** reviews, loyalty, real payments, multi-currency, mobile apps, channel managers.

## 5. Requirements
Functional and non-functional requirements are in `system-design-nuzul.md`, Step 1. They are binding.

## 6. Constraints and assumptions
- Solo developer with Claude Code; CTS framework; about 3 weeks for this app inside the 5–6 week Balanced plan.
- Runs on the owner's existing Oracle Cloud free-tier k3s cluster. AWS is used only for S3 + CloudFront, which costs about $0.
- Java 21, Spring Boot 3.x, Angular (current stable at Sprint 1).

## 7. Risks
| Risk | Mitigation |
|---|---|
| ES + Kafka + Keycloak + PG + Redis exceed free-tier RAM | Measure in Sprint 0; ES heap 512 MB, single-broker KRaft Kafka, Loki instead of ELK |
| Scope creep (reviews, maps…) | YAGNI; the extensions list is the backlog, not v1 |
| Time taken from job outreach | Sprints are 30–60 min batches; outreach cadence is not dropped |

## 8. Timeline
Sprint 0 foundation (½ day) · S1 catalog + inventory + guard · S2 reservations + idempotency + expiry ·
S3 search (ES) + outbox/Kafka + email · S4 Angular guest flow · S5 admin UI + S3 photos · S6 Helm/k3s + Terraform + observability + v1.0.
