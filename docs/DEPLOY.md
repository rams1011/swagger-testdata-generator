# Deploy Test Data Studio API

The API is a Spring Boot JAR (`testdata-generator-api`). Choose a **hosting profile** (`local`, `docker`, `datacenter`, `cloud`) and a **JDBC database** — see [DATABASE.md](DATABASE.md).

## Required environment (hosted profiles)

| Variable | Required | Notes |
|----------|----------|--------|
| `SPRING_PROFILES_ACTIVE` | Yes | One of `docker`, `datacenter`, `cloud` |
| `SPRING_DATASOURCE_URL` | Yes | Full JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Yes | |
| `SPRING_DATASOURCE_PASSWORD` | Yes | |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | No | Set when Boot cannot infer from URL (MySQL, SQL Server, etc.) |
| `TESTDATA_EDITION` | Yes for prod | `local`, `datacenter`, or `saas` |
| `TESTDATA_SAAS_ADMIN_EMAIL` | When `saas` / Cloud Run | Bootstrap operator |
| `TESTDATA_SAAS_ADMIN_PASSWORD` | When `saas` / Cloud Run | Bootstrap operator |
| `TESTDATA_SAAS_CORS_ORIGINS` | Cloud + browser UI | Comma-separated Spring origin patterns. Any port is `:[*]`, for example `https://*.run.app,http://localhost:[*],http://127.0.0.1:[*]` |
| `TESTDATA_AUTH_OIDC_*` | Optional | Google sign-in alongside local password; see Cloud Run table below |

## Docker Compose (docker profile)

From the repository root:

```bash
docker compose up --build
```

API: [http://localhost:8083](http://localhost:8083) — Postgres on `localhost:5432`, database `testdata_studio`, user/password `testdata`. Edition is `local` (no login) for a quick stack.

Copy [.env.example](../.env.example) to `.env` to override credentials or edition.

## Docker image only

The image defaults to `SPRING_PROFILES_ACTIVE=docker`. You must supply JDBC settings:

```bash
docker build -t testdata-studio:latest .
docker run --rm -p 8083:8083 \
  -e SPRING_PROFILES_ACTIVE=docker \
  -e TESTDATA_EDITION=datacenter \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/testdata_studio \
  -e SPRING_DATASOURCE_USERNAME=testdata \
  -e SPRING_DATASOURCE_PASSWORD=secret \
  testdata-studio:latest
```

## Datacenter

VM install, external config files, LDAP or local sign-in, and smoke checks: [DATACENTER.md](DATACENTER.md).

## Datacenter / Kubernetes

Set the hosting profile and datasource on the pod (Secrets → env):

```yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: datacenter
  - name: TESTDATA_EDITION
    value: datacenter
  - name: SPRING_DATASOURCE_URL
    valueFrom:
      secretKeyRef:
        name: testdata-studio-db
        key: url
  - name: SPRING_DATASOURCE_USERNAME
    valueFrom:
      secretKeyRef:
        name: testdata-studio-db
        key: username
  - name: SPRING_DATASOURCE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: testdata-studio-db
        key: password
```

Run Flyway once on first start (enabled automatically for hosted profiles).

## Google Cloud Run (cloud profile)

Workflow: [`.github/workflows/deploy-gcp.yml`](../.github/workflows/deploy-gcp.yml).

### GitHub configuration

**Repository secrets**

| Secret | Purpose |
|--------|---------|
| `SPRING_DATASOURCE_URL` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | Database password |
| `TESTDATA_SAAS_ADMIN_EMAIL` | Bootstrap admin email |
| `TESTDATA_SAAS_ADMIN_PASSWORD` | Bootstrap admin password |
| `TESTDATA_AUTH_OIDC_CLIENT_ID` | Google OAuth Web client id (when `TESTDATA_AUTH_OIDC_ENABLED=true`) |
| `TESTDATA_AUTH_OIDC_CLIENT_SECRET` | Google OAuth client secret |

**Repository variables**

| Variable | Purpose |
|----------|---------|
| `GCP_PROJECT_ID` | GCP project |
| `GCP_REGION` | e.g. `us-central1` |
| `GCP_ARTIFACT_REPO` | Artifact Registry repo name |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | WIF provider resource |
| `GCP_SERVICE_ACCOUNT` | Deploy service account |
| `TESTDATA_SAAS_CORS_ORIGINS` | Studio UI origin patterns, e.g. `https://*.run.app,http://localhost:[*],http://127.0.0.1:[*]` |
| `STUDIO_API_URL` | Public API Cloud Run URL (no trailing slash) |
| `STUDIO_UI_URL` | Public UI Cloud Run URL (no trailing slash) |
| `TESTDATA_AUTH_OIDC_ENABLED` | `true` to enable Google sign-in |
| `TESTDATA_AUTH_OIDC_ISSUER` | Optional; default `https://accounts.google.com` |
| `TESTDATA_AUTH_OIDC_REDIRECT_URI` | Optional if `STUDIO_API_URL` is set — defaults to `{STUDIO_API_URL}/api/auth/oidc/callback` |
| `TESTDATA_AUTH_OIDC_UI_BASE` | Optional if `STUDIO_UI_URL` is set |
| `TESTDATA_AUTH_OIDC_ALLOW_REGISTRATION` | Optional; default `true` for SaaS |

Register the redirect URI on the Google OAuth client. Match **External** consent screen + test users for non-org Gmail accounts.

### Runtime on Cloud Run

The deploy workflow sets:

- `SPRING_PROFILES_ACTIVE=cloud`
- `TESTDATA_EDITION=saas`
- `SPRING_DATASOURCE_*` from the secrets above
- `TESTDATA_AUTH_OIDC_*` when `TESTDATA_AUTH_OIDC_ENABLED=true`
- `SERVER_PORT=8080`

**AlloyDB:** put the full JDBC URL in `SPRING_DATASOURCE_URL` (public IP or connector URL — examples in [DATABASE.md](DATABASE.md)). Grant the Cloud Run **runtime** service account `roles/alloydb.client` when using the Java connector.

**VPC:** if the database is private IP only, configure Cloud Run VPC access (network, subnet, egress) in GCP; the JDBC URL must use an address reachable from the service.

### Manual deploy

```bash
mvn -pl testdata-generator-api -am -DskipTests package
gcloud run deploy testdata-studio \
  --source . \
  --region=us-central1 \
  --set-env-vars="SPRING_PROFILES_ACTIVE=cloud,TESTDATA_EDITION=saas,SERVER_PORT=8080" \
  --set-secrets="SPRING_DATASOURCE_URL=SPRING_DATASOURCE_URL:latest,..."
```

Prefer the GitHub workflow for repeatable releases.

## CI

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs unit tests and Flyway integration tests (PostgreSQL + MySQL via Testcontainers) on push/PR.

## Local development

Default hosting profile is `local` (embedded H2). No Docker required:

```bash
mvn -pl testdata-generator-api -am spring-boot:run
```
