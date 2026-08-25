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
| `POST` | `/api/specs` | Load spec from `location` (URL/path) or `content` |
| `POST` | `/api/specs/upload` | Upload a Swagger/OpenAPI file |
| `GET` | `/api/specs/{specId}` | Spec summary |
| `GET` | `/api/specs/{specId}/operations` | Operation list (dynamic pages) |
| `GET` | `/api/specs/{specId}/operations/{key}` | JSON Schema, samples, and defaults for one operation |
| `GET` | `/api/specs/{specId}/plans` | List test plans for a spec (creates **Default** on first access) |
| `POST` | `/api/specs/{specId}/plans` | Create a named test plan |
| `PUT` | `/api/specs/{specId}/plans/{planId}` | Rename a test plan |
| `DELETE` | `/api/specs/{specId}/plans/{planId}` | Delete a plan and its cases (the last plan cannot be deleted) |
| `POST` | `/api/testdata` | Save a test case into a plan (`planId`) |
| `PUT` | `/api/testdata/{id}` | Update a test case |
| `GET` | `/api/testdata?specId=&planId=&operationKey=` | List saved cases for a plan |
| `DELETE` | `/api/testdata/{id}` | Delete a case |
| `POST` | `/api/testdata/export` | Download Excel for one plan (`specId` + `planId`) |

## Storage (Excel, H2, or PostgreSQL)

The API persists loaded specs and saved test cases to Excel, H2, or PostgreSQL. Switch with `testdata.storage.type` in `testdata-generator-api/src/main/resources/application.yml`.

```yaml
testdata:
  storage:
    type: excel   # excel | h2 | database
```

### Excel

Default. Specs, test plans, test cases, and path/query/header params are written to sheets `Specs`, `Plans`, `TestCases`, and `Params` in `testdata.excel.file` (default `./studio-output/studio-testdata.xlsx`). OpenAPI documents are stored next to the workbook under `specs/`.

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

On startup the API runs `classpath:ddl/schema.sql` (PostgreSQL-compatible H2 mode). Data is stored as `./studio-output/testdata.mv.db`. Hand-apply DDL is in `testdata-generator-api/src/main/resources/ddl/h2.sql`.

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
| `td_spec` | Loaded OpenAPI specs (source URL/content, title, base URL) |
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
