# DevOps / DevSecOps: Nuzul

Owner: DevOps · Status: DRAFT

## CI (GitHub Actions), on every push and PR
1. `api`: `./mvnw verify` (unit + Testcontainers + JaCoCo) → **SonarCloud** scan and quality gate.
2. `web`: `npm ci && npm run lint && npm test && npm run build`.
3. Build images (Jib for api, multi-stage nginx for web) → **Trivy** scan (fail on CRITICAL) → push to GHCR on `main`.
4. `helm lint` + `helm template | kubeconform`.
5. `terraform fmt -check && terraform validate` for `infra/terraform/aws`.
6. E2E job (on `main` only): compose up → Playwright → upload trace and video.

## CD
On `main` with green CI: bump the image tag in `deploy/helm/nuzul/values-prod.yaml`. Argo CD (already on the owner's k3s)
syncs the release. A manual approval environment is required for prod.

## Runtime (k3s on OCI free tier, ARM64)
| Component | How | Memory budget |
|---|---|---|
| nuzul-api ×2 | Helm Deployment, HPA 2–4, readiness on `/actuator/health/readiness` | 2 × 512 MB |
| nuzul-web | nginx Deployment | 32 MB |
| Postgres 16 | CloudNativePG, 1 instance, nightly backup to S3 | 512 MB |
| Redis 7 | Bitnami chart, standalone | 128 MB |
| Elasticsearch 8 | ECK, 1 node, heap 512 MB | 1.25 GB |
| Kafka | Strimzi KRaft, 1 broker | 768 MB |
| Keycloak | Helm, `start --optimized` | 768 MB |
| Observability | kube-prometheus-stack (slim) + Loki + Tempo | ~1.2 GB |
Total is about 5.5 GB, inside the 24 GB free-tier Ampere allowance shared with existing workloads.

### Measured in Sprint 0 (2026-11-17, `docker stats`, compose, idle after warm-up, no seed data)
| Component | Measured | Budget | Verdict |
|---|---|---|---|
| nuzul-api (Jib image, skeleton) | 389 MB | 512 MB | OK — re-measure under k6 load in Sprint 6 |
| nuzul-web (nginx) | 8 MB | 32 MB | OK |
| Postgres 16 | 48 MB | 512 MB | OK (empty DB) |
| Redis 7 | 4 MB | 128 MB | OK |
| Elasticsearch 8 (heap 512 MB) | 1,044 MB | 1 GB | **Tight** → budget 1.25 GB |
| Kafka 4.4 KRaft (heap ≤ 512 MB) | 433 MB | 768 MB | OK |
| Keycloak 26 (`start-dev`) | 768 MB | 512 MB | **Over** → budget 768 MB; prod `start --optimized` + `-XX:MaxRAMPercentage` |

Measured app stack: 2 × 389 + 8 + 48 + 4 + 1,044 + 433 + 768 ≈ **3.1 GB**. With observability (~1.2 GB, unmeasured)
and the revised ES/Keycloak budgets the plan is **≈ 6 GB**, still well inside the 24 GB allowance. **k3s budget confirmed.**

## AWS (Terraform, `infra/terraform/aws`)
S3 bucket `nuzul-photos-<suffix>` (private, versioned, lifecycle to IA after 90 days) · CloudFront with OAC ·
IAM user scoped to `s3:PutObject` on the bucket for presigning · remote state in S3 + DynamoDB lock. About $0 inside the free tier.

## Environment variables (CTS rule 10 — values needed before EXECUTE)
```
DB_URL, DB_USER, DB_PASSWORD
REDIS_URL
ES_URL, ES_USER, ES_PASSWORD
KAFKA_BOOTSTRAP
OIDC_ISSUER_URI, OIDC_AUDIENCE
S3_BUCKET, S3_REGION, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, CDN_BASE_URL
SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD, MAIL_FROM
SONAR_TOKEN (CI secret), GHCR via GITHUB_TOKEN
APP_HOLD_MINUTES=10, APP_CORS_ORIGIN
```
Local dev needs no real values; compose provides all of them. Prod values are needed at Sprint 6 only: **an AWS account and a domain/subdomain**.
