# Test Data Generator

Maven multi-module project that turns a Swagger 2.0 or OpenAPI 3.x spec into api-automation test data.

| Module | What it is |
|--------|------------|
| `testdata-generator-core` | Library + CLI JAR. Parse OpenAPI, inspect operations, generate Excel/payloads/schemas. |
| `testdata-generator-api` | Spring Boot REST API. Depends on the core library. |
| [`testdata-studio-ui`](D:/code/testdata-studio-ui) | Separate React app. Dynamic pages/forms from the extracted schemas. |

## Build

```bash
cd D:\code\swagger-testdata-generator
mvn clean package
```

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

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/specs` | Load spec from `location` or `content` (requires unique `name`) |
| `POST` | `/api/specs/upload` | Upload a Swagger/OpenAPI file (requires unique `name`) |
| `PUT` | `/api/specs/{specId}` | Rename a spec (`name` must stay unique) |
| `DELETE` | `/api/specs/{specId}` | Delete a spec and its plans/cases |
| `POST` | `/api/specs/{specId}/reload` | Re-fetch the spec in place (same `specId`); optional `location`, `content`, `baseUrl` |
| `POST` | `/api/specs/{specId}/reload/upload` | Replace the spec document from a file, keeping the same `specId` |
| `POST` | `/api/testdata/{id}/files` | Upload a multipart request file owned by that test case |
| `GET` | `/api/testdata/{id}/files/{fileId}` | Download a stored request file |
| `GET` | `/api/specs` | List loaded specs |
| `GET` | `/api/specs/{specId}` | Spec summary |
| `GET` | `/api/specs/{specId}/operations` | Operation list (dynamic pages) |
| `GET` | `/api/specs/{specId}/operations/{key}` | JSON Schema, samples, and defaults for one operation |
| `GET` | `/api/specs/{specId}/plans` | List test plans for a spec (creates **Default** on first access) |
| `POST` | `/api/specs/{specId}/plans` | Create a named test plan |
| `PUT` | `/api/specs/{specId}/plans/{planId}` | Rename a test plan |
| `DELETE` | `/api/specs/{specId}/plans/{planId}` | Delete a plan and its cases (the last plan cannot be deleted) |
| `POST` | `/api/testdata` | Save a test case into a plan (`planId`) |
| `POST` | `/api/testdata/generate` | Auto-generate positive and negative cases from the spec/operation schema into a plan |
| `POST` | `/api/testdata/generate-plans` | Create/fill a **Smoke** plan (one happy path per operation) and an optional **Negative** plan |
| `PUT` | `/api/testdata/{id}` | Update a test case |
| `GET` | `/api/testdata?specId=&planId=&operationKey=` | List saved cases for a plan |
| `DELETE` | `/api/testdata/{id}` | Delete a case |
| `POST` | `/api/testdata/export` | Download Excel for one plan (`specId` + `planId`) |
| `POST` | `/api/testdata/run` | Run the current (unsaved) case; body is `{ testCase, auth }` |
| `POST` | `/api/testdata/{id}/run` | Run a saved case with optional `{ auth }` |
| `POST` | `/api/testdata/run-plan` | Run all enabled cases in a plan (`specId`, `planId`, `auth`) |

Auth is applied only for that run and is not stored with the case. `auth.type` is `NONE`, `BASIC` (credentials: `username` / `password`), `BEARER` (`token`), or `API_KEY` (`apiKey`, optional `apiKeyName`, `apiKeyIn` of `header` or `query`). The runner checks expected status, max response time, and JSONPath assertions against the live response.

### Auto-generate cases

`POST /api/testdata/generate` walks each selected operation schema and saves a bounded set of cases into the current plan:

- Happy path with sample params/body and the first documented 2xx status
- Missing required body, query, and header fields (up to five each)
- Invalid enum and type on the first matching field
- minLength / maxLength / minimum / maximum violations
- Extra property when `additionalProperties: false`
- Documented 404 (bogus path id) and 401/403 (invalid `Authorization`)

Pass `operationKey` to generate for one operation, or omit it for every operation in the spec. Existing case names in the plan are skipped. The studio UI exposes this as **Generate cases** on an operation page and **Fill this plan** on the saved-cases page.

`POST /api/testdata/generate-plans` find-or-creates a **Smoke** plan with one happy-path case per operation, and optionally a **Negative** plan with schema-derived negatives. Existing plan names are reused and duplicate case names are skipped. The studio UI exposes this as **Generate Smoke plan** (with **Also Negative plan**) in the sidebar and on the saved-cases page.

## Storage (Excel, H2, or PostgreSQL)

The API persists loaded specs and saved test cases to Excel, H2, or PostgreSQL. Switch with `testdata.storage.type` in `testdata-generator-api/src/main/resources/application.yml`.

```yaml
testdata:
  storage:
    type: excel   # excel | h2 | database
```

### Excel

Default. Specs, test plans, test cases, and path/query/header params are written to sheets `Specs`, `Plans`, `TestCases`, and `Params` in `testdata.excel.file` (default `./studio-output/studio-testdata.xlsx`). OpenAPI documents are stored next to the workbook under `specs/`. Multipart request files are stored under `studio-files/{testCaseId}/`.

### H2

Embedded file database. No extra server required. Setting `type: h2` applies the JDBC URL, driver, schema, and optional console automatically.

```yaml
testdata:
  storage:
    type: h2
  h2:
    file: ./studio-output/testdata
    username: sa
    password: ""
    console: true
    in-memory: false
```

On startup the API runs `classpath:ddl/schema.sql` (PostgreSQL-compatible H2 mode). Data is stored as `./studio-output/testdata.mv.db`. Hand-apply DDL is in `testdata-generator-api/src/main/resources/ddl/h2.sql`. Multipart request files are stored in `td_request_file` keyed by `test_case_id` (the case already belongs to a spec) and written to `testdata.default-output-dir/files` on Excel export (`UploadFiles` column uses `testdata.files-path-prefix`).

H2 console: [http://localhost:8083/h2-console](http://localhost:8083/h2-console)

Do not use the console default `jdbc:h2:~/test`. That path is `C:/Users/<you>/test` and H2 2.x will refuse to create it from the console (error 90149). Start the API first, then paste the JDBC URL from the startup log or `GET /api/testdata/config`.

| Field | Value |
|-------|-------|
| JDBC URL | logged at startup, also `h2JdbcUrl` from `GET /api/testdata/config` |
| User | `sa` |
| Password | empty |

Set `testdata.h2.in-memory: true` for a throwaway database that is wiped on restart. Set `testdata.h2.console: false` to disable the console.

### PostgreSQL

```yaml
testdata:
  storage:
    type: database
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/testdata_studio
    username: testdata
    password: testdata
```

On startup the API runs `classpath:ddl/schema.sql`. PostgreSQL-specific DDL (comments, grants) is in `testdata-generator-api/src/main/resources/ddl/postgresql.sql`.

### Tables

| Table | Purpose |
|-------|---------|
| `td_spec` | Loaded OpenAPI specs (unique `name`, source URL/content, OpenAPI title, base URL) |
| `td_test_plan` | Named test plans under a spec. A spec always has at least one plan (`Default` is created on first access) |
| `td_test_case` | Test cases created from operation schemas (`plan_id` scopes a case to a plan) |
| `td_test_case_param` | Path, query, and header values (`param_in` = `path` \| `query` \| `header`) |

One OpenAPI spec can have many test plans. Saved cases and Excel export are scoped to a single plan. Existing cases without `plan_id` are attached to **Default** the first time plans are loaded.

`POST /api/testdata/export` maps saved cases for one plan from storage (H2, PostgreSQL, or the internal studio workbook) into a downloaded api-automation workbook:

- Browser download (default file name is the plan name, e.g. `Default.xlsx`)
- Single sheet `TestData` with api-automation headers (`TestName`, `BaseUrl`, `HttpMethod`, …)
- Request bodies are inlined in `RequestBody`

Studio Excel storage (`studio-testdata.xlsx` with sheets Specs / Plans / TestCases / Params) is only an internal store. It is never used as the api-automation input file.

## React UI

```bash
cd D:\code\testdata-studio-ui
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173). Vite proxies `/api` to the API at [http://localhost:8083](http://localhost:8083).

Use the **Test plan** control in the sidebar to switch plans, add a plan, or delete a plan (the last plan is kept). Saved cases, the operation form, and **Download Excel** all apply to the selected plan only.

**Reload spec** (home list or sidebar) replaces the OpenAPI document for that spec id. Plans and saved cases are not deleted. New and changed operations appear immediately. Cases whose `operationKey` disappeared stay in the plan as orphans (counted in the reload result) until you delete them. The stored base URL is kept unless you pass a new one. **Load spec** still creates a *new* spec id and leaves existing data untouched. Each spec has a unique **name** (required when loading); the OpenAPI `info.title` is stored separately and can stay the same across specs.

**Run** on an operation page executes the current form (save is not required). **Run plan** on the saved-cases page runs every enabled case. Set **Run authentication** to credentials (HTTP Basic), a bearer token, or an API key; the values stay in browser `sessionStorage` for that spec and are not written to Excel or the studio database. Choose **None** when a case already sends `Authorization` (for example generated 401 tests).

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
