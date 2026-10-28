# Test Data Generator

Maven multi-module project that turns a Swagger 2.0 or OpenAPI 3.x spec into api-automation test data.

The API module uses **Spring Boot 4.1.1**. A default build targets **Java 21**. To run the same JAR on **Java 17 or Java 21**, package with release 17 (build on JDK 17 or 21):

```bash
mvn clean install -Djava.version=17
```

Do not produce a separate JAR per Java version. On one machine, run the API and the Studio UI on the same JDK.

| Module | What it is |
|--------|------------|
| `testdata-generator-core` | Library + CLI JAR. Parse OpenAPI, inspect operations, generate Excel/payloads/schemas. |
| `testdata-generator-api` | Spring Boot REST API. Depends on the core library. |
| [`testdata-studio-ui`](D:/code/testdata-studio-ui) | Separate React app. Dynamic pages/forms from the extracted schemas. |

## Build

Always build from the **repository root** so `testdata-generator-core` is compiled before the API module:

```bash
cd D:\code\swagger-testdata-generator
mvn clean install
```

If you only changed the core library and compile the API alone, install core first (otherwise Maven uses an older core JAR from `~/.m2` and you may see errors such as missing `TestDataRow.builder().extracts(...)` — that is a stale dependency, not a missing Lombok plugin):

```bash
mvn install -pl testdata-generator-core -DskipTests
# or
mvn clean install -pl testdata-generator-api -am
```

**Lombok** is used only in `testdata-generator-core` (`@Getter`, `@Builder`). The parent POM configures `maven-compiler-plugin` with Lombok on the annotation processor path. In Cursor/VS Code, use the **Extension Pack for Java** and enable **Annotation processing** in Java settings (`java.import.maven.annotationProcessing`: `automatic`).

Artifacts:

- Library: `testdata-generator-core/target/testdata-generator-core-1.0.0-SNAPSHOT.jar`
- CLI fat JAR: `testdata-generator-core/target/testdata-generator-core-1.0.0-SNAPSHOT-cli.jar`
- API: `testdata-generator-api/target/testdata-generator-api-1.0.0-SNAPSHOT.jar`

## CLI

```bash
java -jar testdata-generator-core/target/testdata-generator-core-1.0.0-SNAPSHOT-cli.jar ^
  --swagger https://petstore.swagger.io/v2/swagger.json ^
  --output D:\code\api-automation\src\test\data\TestData.xlsx ^
  --payload-dir D:\code\api-automation\src\test\data\payloads ^
  --schema-dir D:\code\api-automation\src\test\data\schemas
```

### Options

| Option | Description |
|--------|-------------|
| `-s, --swagger` | Swagger/OpenAPI URL or local file (required) |
| `-o, --output` | Output Excel path (default: `./TestData.xlsx`) |
| `-b, --base-url` | Override base URL column |
| `-g, --test-group` | Default test group when operation has no tags |
| `-e, --enabled` | Enabled column value (default: `Y`) |
| `-t, --max-response-time` | Max response time in ms (default: `3000`) |
| `-p, --payload-dir` | Directory for generated request JSON files |
| `--schema-dir` | Directory for generated response JSON Schema files |
| `--payload-prefix` | `file:` path prefix for payloads (default: `src/test/data/payloads`) |
| `--schema-prefix` | `file:` path prefix for schemas (default: `src/test/data/schemas`) |
| `--include` | Regex filter to include matching operations |
| `--exclude` | Regex filter to exclude matching operations |

The same generator is a Maven dependency for the API:

```xml
<dependency>
    <groupId>com.tx.api.test</groupId>
    <artifactId>testdata-generator-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Library entry point: `com.tx.api.test.generator.TestdataGenerator`.

## API

```bash
mvn -pl testdata-generator-api -am spring-boot:run
```

API base: [http://localhost:8083](http://localhost:8083)

### Deploy

| Target | Guide |
|--------|--------|
| Docker Compose + Postgres | `docker compose up --build` — see [docs/DEPLOY.md](docs/DEPLOY.md) |
| Datacenter / Kubernetes | [docs/DEPLOY.md](docs/DEPLOY.md) — `SPRING_PROFILES_ACTIVE=datacenter` + JDBC secrets |
| Google Cloud Run | GitHub Actions [deploy-gcp.yml](.github/workflows/deploy-gcp.yml) — `SPRING_PROFILES_ACTIVE=cloud` |

Database URLs and drivers: **[docs/DATABASE.md](docs/DATABASE.md)**. Copy [.env.example](.env.example) for Compose overrides.

CI runs on push/PR via [ci.yml](.github/workflows/ci.yml) (unit + Flyway on PostgreSQL and MySQL).

### Hosting profile (Spring)

The API requires **exactly one** Spring profile: `local`, `docker`, `datacenter`, or `cloud`. Startup fails if none or more than one is active.

| Profile | Set via | Use |
|---------|---------|-----|
| `local` | `spring.profiles.default` in `application.yml` when unset (or `SPRING_PROFILES_ACTIVE=local`) | Developer machine |
| `docker` | `SPRING_PROFILES_ACTIVE=docker` (default in the Docker image) | Containers on your network |
| `datacenter` | `SPRING_PROFILES_ACTIVE=datacenter` | VM / on-prem Kubernetes |
| `cloud` | `SPRING_PROFILES_ACTIVE=cloud` (Cloud Run deploy) | Managed cloud runtime |

Profiles `docker`, `datacenter`, and `cloud` also activate the shared `hosted` profile (Flyway on, H2 console off). Set `SPRING_DATASOURCE_*` for any JDBC engine.

`GET /api/testdata/config` includes `hostingProfile`.

### Hosting mode

Set **one** variable: `TESTDATA_EDITION`. Do not also set a SaaS on/off flag.

| `TESTDATA_EDITION` | Login | Plan | Typical use |
|--------------------|-------|------|-------------|
| `local` | No | No | Desktop |
| `datacenter` (default) | Yes | No | On-prem / data center |
| `saas` | Yes | Yes | Hosted product |

```bash
# Desktop
TESTDATA_EDITION=local

# Data center (login + admin, no plan)
TESTDATA_EDITION=datacenter
SPRING_PROFILES_ACTIVE=datacenter
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/testdata_studio
TESTDATA_SAAS_ADMIN_EMAIL=admin@company.example
TESTDATA_SAAS_ADMIN_PASSWORD=change-me
```

The API jar does not contain an admin email or password. Datacenter local sign-in exits on startup until both variables are set.

If `TESTDATA_EDITION` is blank or unset, Studio uses `datacenter`. The sample `application.yml` defaults to `local` for desktop dev. Cloud Run must set `TESTDATA_EDITION=saas`.

Public register is off in `datacenter`. The bootstrap admin adds people from **Admin** (or users provision on first LDAP sign-in).

### Sign-in provider

`TESTDATA_AUTH_PROVIDER` is `local` (default) or `ldap`. Ignored when `TESTDATA_EDITION=local`.

| Provider | Behavior |
|----------|----------|
| `local` | `POST /api/auth/login` checks PBKDF2 password in `td_account`. |
| `ldap` | API binds to the directory (see below), then issues the same Bearer session as local login. Directory passwords are not stored. |

**Minimal LDAP** (Active Directory–style UPN bind: `user@domain`):

```bash
TESTDATA_AUTH_PROVIDER=ldap
TESTDATA_LDAP_URL=ldaps://ldap.client.com:636
TESTDATA_LDAP_DOMAIN=client.com
```

**Optional LDAP** (env names mirror `testdata.auth.ldap.*` in `application.yml`): `TESTDATA_LDAP_MANAGER_DN`, `TESTDATA_LDAP_MANAGER_PASSWORD`, `TESTDATA_LDAP_BASE_DN`, `TESTDATA_LDAP_USER_SEARCH_BASE`, `TESTDATA_LDAP_USER_SEARCH_FILTER`, `TESTDATA_LDAP_ALLOWED_GROUP`, `TESTDATA_LDAP_GROUP_ROLE_OWNER` / `_ADMIN` / `_MEMBER`, `TESTDATA_LDAP_ALLOW_INSECURE`, `TESTDATA_AUTH_LOCAL_ADMIN_FALLBACK` (default `true`).

Startup fails if `provider=ldap` and URL or domain is missing. Plain `ldap://` is rejected unless `TESTDATA_LDAP_ALLOW_INSECURE=true`.

`GET /api/testdata/config` includes `authProvider` (`local`, `ldap`, `none`) and `loginHint` (e.g. `you@client.com`) with no directory secrets.

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/auth/login` | Sign in (local or LDAP) |
| `POST` | `/api/auth/register` | SaaS only — new org + trial |
| `GET` | `/api/auth/session` | Current session (public) |
| `POST` | `/api/auth/logout` | End session |
| `POST` | `/api/auth/switch-tenant` | Change active workspace |
| `GET` | `/api/tenants/current/members` | List workspace members |
| `POST` | `/api/tenants/current/members` | Grant workspace access (`accountId`, `role`) — org member required |
| `PUT` | `/api/tenants/current/members/{accountId}` | Change role |
| `DELETE` | `/api/tenants/current/members/{accountId}` | Remove member |
| `GET` | `/api/organizations/current/members` | List organization roster (org or workspace admin) |
| `POST` | `/api/organizations/current/members` | Add person to organization roster (no workspace access yet) |
| `PUT` | `/api/organizations/current/members/{accountId}` | Change organization role |
| `DELETE` | `/api/organizations/current/members/{accountId}` | Remove from org and all org workspaces |
| `GET` | `/api/organizations/current/workspaces` | List workspaces in current organization |
| `GET` | `/api/testdata/config` | Edition, storage, auth flags for the UI |

Organization / workspace model and rollout: **[docs/ADR-001-organization-workspace-access.md](docs/ADR-001-organization-workspace-access.md)**, **[docs/ORG-WORKSPACE-ROLLOUT.md](docs/ORG-WORKSPACE-ROLLOUT.md)**.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/health` | Liveness. Adds a JDBC ping (`database: UP`) when storage is H2 or PostgreSQL |
| `POST` | `/api/specs` | Load spec from `location` or `content` (requires unique `name`) |
| `POST` | `/api/specs/upload` | Upload a Swagger/OpenAPI file (requires unique `name`) |
| `PUT` | `/api/specs/{specId}` | Update spec `name` (must stay unique) and/or `baseUrl`. Saved cases that still used the previous spec URL are updated |
| `DELETE` | `/api/specs/{specId}` | Delete a spec and its plans/cases |
| `POST` | `/api/specs/{specId}/reload` | Re-fetch the spec in place (same `specId`); optional `location`, `content`, `baseUrl` |
| `POST` | `/api/specs/{specId}/reload/upload` | Replace the spec document from a file, keeping the same `specId` |
| `POST` | `/api/testdata/{id}/files` | Upload a multipart request file owned by that test case |
| `GET` | `/api/testdata/{id}/files/{fileId}` | Download a stored request file |
| `GET` | `/api/specs` | List loaded specs |
| `GET` | `/api/specs/{specId}` | Spec summary |
| `GET` | `/api/specs/{specId}/operations` | Operation list (dynamic pages) |
| `POST` | `/api/specs/{specId}/operations` | Add a REST API (manual catalogs and extra Swagger operations) |
| `GET` | `/api/specs/{specId}/operations/{key}` | JSON Schema, samples, and defaults for one operation |
| `GET` | `/api/specs/{specId}/plans` | List test plans for a spec (creates **Default** on first access) |
| `POST` | `/api/specs/{specId}/plans` | Create a named test plan |
| `PUT` | `/api/specs/{specId}/plans/{planId}` | Rename a test plan |
| `DELETE` | `/api/specs/{specId}/plans/{planId}` | Delete a plan and its cases (the last plan cannot be deleted) |
| `POST` | `/api/testdata` | Save a test case into a plan (`planId`). With `environmentId`, the case belongs to that environment |
| `PUT` | `/api/testdata/{id}` | Update a test case |
| `GET` | `/api/testdata?specId=&planId=&operationKey=&environmentId=` | List saved cases for a plan (environment filters the list) |
| `POST` | `/api/testdata/{id}/duplicate` | Copy a case into another environment as a new id (`fromEnvironmentId`, `toEnvironmentId`) |
| `DELETE` | `/api/testdata/{id}` | Delete a case (`?environmentId=` removes it from that environment only) |
| `POST` | `/api/testdata/export` | Download Excel for one plan in the selected environment (`specId`, `planId`, `environmentId`) |
| `POST` | `/api/testdata/run` | Run the current (unsaved) case; body is `{ testCase, auth }` |
| `POST` | `/api/testdata/{id}/run` | Run a saved case with optional `{ auth }` |
| `POST` | `/api/testdata/run-plan` | Run enabled cases in a plan for one environment (`specId`, `planId`, `auth`, `environmentId`) |
| `GET` | `/api/runs?specId=&planId=&environmentId=&caseId=&outcome=&trigger=&limit=` | List persisted run batches (H2/Postgres). Newest first |
| `GET` | `/api/runs/{runId}` | One run plus request/response snapshots |
| `GET` | `/api/runs/{runId}/results/{resultId}` | One HTTP snapshot (body still available after the case is edited) |
| `GET` | `/api/runs/compare?leftRunId=&leftResultId=&rightRunId=&rightResultId=` | Field-level diff of two snapshots |
| `DELETE` | `/api/runs/{runId}` | Delete a run batch and its snapshots |

Auth is applied only for that run and is not stored with the case. `auth.type` is `NONE`, `BASIC` (credentials: `username` / `password`), `BEARER` (`token`), or `API_KEY` (`apiKey`, optional `apiKeyName`, `apiKeyIn` of `header` or `query`). The runner checks expected status, max response time, and JSONPath assertions against the live response.

## Storage (JDBC)

The API persists specs, plans, cases, environments, run history, and uploads in a **relational database** only. Configuration is standard Spring Boot:

- **`local` hosting profile** — embedded H2 file DB (`application-local.yml` + `testdata.h2.*`)
- **`docker` / `datacenter` / `cloud`** — `SPRING_DATASOURCE_*` (see `application-hosted.yml`)

`POST /api/testdata/export` still downloads an api-automation **Excel workbook** (export format only — not the system of record).

### H2 (local hosting profile)

Embedded file database. No extra server required.

```yaml
# application-local.yml (active with SPRING_PROFILES_ACTIVE=local)
testdata:
  h2:
    file: ./studio-output/testdata
    username: sa
    password: ""
    console: true
    in-memory: false
  run-history:
    max-per-spec: 100
```

On startup Flyway applies `classpath:db/migration` (`V1__studio.sql`, same as `ddl/schema.sql`). Existing databases are baselined at version 1. Data is stored as `./studio-output/testdata.mv.db`. File H2 is opened with `AUTO_SERVER=TRUE` so api-automation can connect while Studio is running; restart Studio after upgrading for that to take effect. Hand-apply DDL is in `testdata-generator-api/src/main/resources/ddl/h2.sql`. Multipart request files are stored in `td_request_file`. Excel export downloads a zip (`workbook.xlsx` plus `files/<caseId>/`) when cases have uploads — it does not write into an api-automation checkout.

After each Try / Run / Run plan, the oldest batches for that spec are deleted once the spec has more than `testdata.run-history.max-per-spec` runs (default `100`). Set `0` to keep history unbounded.

The H2 console is off by default. Set `testdata.h2.console: true` (or `TESTDATA_H2_CONSOLE=true`) for local debugging only, then open [http://localhost:8083/h2-console](http://localhost:8083/h2-console).

Do not use the console default `jdbc:h2:~/test`. That path is `C:/Users/<you>/test` and H2 2.x will refuse to create it from the console (error 90149). Start the API first, then paste the JDBC URL from the startup log or `GET /api/testdata/config` (those fields are only returned when the console is on).

| Field | Value |
|-------|-------|
| JDBC URL | logged at startup, also `h2JdbcUrl` from `GET /api/testdata/config` when the console is on |
| User | `sa` |
| Password | empty |

For an in-memory H2 database, set `TESTDATA_H2_JDBC_URL` (see [docs/DATABASE.md](docs/DATABASE.md)).

### External database (docker, datacenter, cloud)

Hosting profiles `docker`, `datacenter`, and `cloud` activate the `hosted` profile group, which enables Flyway and reads JDBC settings from the environment. Point Spring at **any JDBC database** (PostgreSQL, MySQL, MariaDB, etc.):

```bash
SPRING_PROFILES_ACTIVE=cloud
SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/testdata_studio?sslmode=require
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=...
# SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver  # optional when Boot infers from URL
```

On startup Flyway applies `classpath:db/migration`. `baseline-on-migrate=true` marks version 1 as applied on an existing schema so V1 is not re-run. Set `SPRING_DATASOURCE_*` in the environment — values are not committed.

**Drivers:** PostgreSQL, MySQL 8, H2, and AlloyDB connector JARs are on the API classpath. **CI** runs unit tests (H2) and Flyway integration tests against PostgreSQL 16 and MySQL 8.4 (Testcontainers).

Full JDBC examples (Postgres, MySQL, AlloyDB public IP and connector URL): **[docs/DATABASE.md](docs/DATABASE.md)**.

Cloud Run secrets, CORS, and AlloyDB notes: **[docs/DEPLOY.md](docs/DEPLOY.md)**.

### Tables

| Table | Purpose |
|-------|---------|
| `td_spec` | Loaded OpenAPI specs (unique `name`, source URL/content, OpenAPI title, base URL) |
| `td_test_plan` | Named test plans under a spec. A spec always has at least one plan (`Default` is created on first access) |
| `td_test_case` | Test cases created from operation schemas (`plan_id` scopes a case to a plan) |
| `td_test_case_param` | Path, query, and header values (`param_in` = `path` \| `query` \| `header`) |
| `td_run` | One Try / Run / Run plan click (`run_trigger` = `try` \| `case` \| `plan`; `environment_id` scopes history per environment) |
| `td_run_result` | Snapshot of one HTTP call (redacted headers, truncated body) |

Oldest `td_run` rows for a spec are deleted after each write when the count exceeds `testdata.run-history.max-per-spec` (default `100`; `0` keeps history unbounded).

One OpenAPI spec can have many test plans. Saved cases and Excel export are scoped to a single plan. Existing cases without `plan_id` are attached to **Default** the first time plans are loaded.

`POST /api/testdata/export` maps saved cases for one plan from the JDBC store into a downloaded api-automation workbook:

- Browser download (default file name is the plan name, e.g. `Default.xlsx`)
- Single sheet `TestData` with api-automation headers (`TestName`, `BaseUrl`, `HttpMethod`, …)
- Request bodies are inlined in `RequestBody`

## React UI

```bash
cd D:\code\testdata-studio-ui
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173). Vite proxies `/api` to the API at [http://localhost:8083](http://localhost:8083).

Use the **Test plan** control in the sidebar to switch plans, add a plan, or delete a plan (the last plan is kept). Saved cases, the operation form, and **Download Excel** all apply to the selected plan only.

**Reload spec** (home list or sidebar) replaces the OpenAPI document for that spec id. Plans and saved cases are not deleted. New and changed operations appear immediately. Cases whose `operationKey` disappeared stay in the plan as orphans (counted in the reload result) until you delete them. The stored base URL is kept unless you pass a new one. Change the spec **Base URL** in the workspace bar without reloading the document; saved cases that still used the previous spec URL are updated. **Load spec** still creates a *new* spec id and leaves existing data untouched. Each spec has a unique **name** (required when loading); the OpenAPI `info.title` is stored separately and can stay the same across specs.

**Run** on an operation page executes the current form (save is not required). **Run plan** on the saved-cases page runs every enabled case. Set **Run authentication** to credentials (HTTP Basic), a bearer token, or an API key; the values stay in browser `sessionStorage` for that spec and are not written to Excel or the studio database. Choose **None** when a case already sends `Authorization` (for example generated 401 tests).

**Environments** own their cases. Save, update, delete, generate, export, and run-plan apply to the selected environment. **Duplicate into** creates a new case (and files) in another environment. Uploads belong to the case, not a separate env column. Collection export is scoped to the selected environment and stores membership **names**. Restart testdata-generator-api so H2 picks up the latest schema.

**History** lists saved Try / Run / Run plan snapshots (H2 or PostgreSQL). Oldest runs are pruned when a spec exceeds `testdata.run-history.max-per-spec` (default 100). Tick two runs to compare status, timing, assertions, and bodies (older vs newer).

## Output format

The generated Excel file matches the columns expected by `ExcelUtil` in api-automation:

| Column | Description |
|--------|-------------|
| Test Case No | Sequential row number |
| Service Name | Operation summary / operationId |
| Service Base Url | From OpenAPI `servers[0].url` or `--base-url` |
| Request Type | GET, POST, PUT, PATCH, DELETE |
| Endpoint | Path with sample path params substituted |
| Content-Type | `JSON` or `NA` |
| QueryParamKey / QueryParamValue | Pipe-separated query parameters |
| Header Name / Header Value | Pipe-separated non-auth headers |
| Request Body | `file:src/test/data/payloads/{operation}.json` when a JSON body exists |
| Status Code | First 2xx response code from the spec |
| Response Type | `JSON` for GET, otherwise `NONE` |
| Expected Response | `NA` (fill in after running against real API) |
| Enabled | `Y` by default |
| TestGroup | First OpenAPI tag or `generated` |
| JsonPathAssertions | Sample top-level assertions for GET responses |
| JsonSchemaPath | `file:src/test/data/schemas/{operation}.schema.json` when response schema exists |
| MaxResponseTimeMs | Default `3000` |

## Workflow with api-automation

1. Generate or export `TestData.xlsx`, payloads, and schemas from your Swagger spec.
2. Review sample values, expected responses, and enabled flags.
3. Run api-automation tests:

```bash
cd D:\code\api-automation
mvn clean test
```
