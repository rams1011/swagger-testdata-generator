# swagger-testdata-generator (Test Data Studio API)

Maven multi-module project: OpenAPI → test data. **Studio** lives in `testdata-generator-api` (Spring Boot REST, JDBC storage, live HTTP runs, auth, export).

**Sibling UI repo:** `D:\code\testdata-studio-ui` (React/Vite). UI proxies to this API (default `:8083`).

## Build and run

Always build from **repo root** when core + API both change:

```powershell
cd D:\code\swagger-testdata-generator
mvn clean install
mvn -pl testdata-generator-api -am spring-boot:run
```

API-only after core install:

```powershell
mvn clean install -pl testdata-generator-api -am
mvn -pl testdata-generator-api -am test
```

Default Java 21; `-Djava.version=17` for Java 17 bytecode. Lombok only in `testdata-generator-core`.

## Module map

| Module | Role |
|--------|------|
| `testdata-generator-core` | OpenAPI parse, schema-based case generation, offline CLI JAR that writes collection + environment JSON (`service/CollectionGenerator`) |
| `testdata-generator-api` | Studio REST, `com.tx.api.test.generator.studio.*` |

**Export format:** collection JSON (`testdata.studio.collection` v1) is the only case export; there is no Excel export. api-automation reads collection JSON (default) or the Studio database over JDBC.

Studio package layout:

| Area | Path under `testdata-generator-api/src/main/java/.../studio/` |
|------|----------------------------------------------------------------|
| HTTP | `api/*Controller.java` |
| Orchestration | `service/TestDataStudioService.java`, `TestRunService.java`, `RunHistoryService.java` |
| Variables / substitution | `service/CaseVariables.java`, `RunVariables.java`, `DynamicVariables.java` |
| Collection formats | `collection/*` (environment, import/export, `StudioVariables`, `StudioEnvironment`) |
| Persistence | `storage/StudioStorage.java`, `JdbcStudioStorage.java`, `ForwardingStudioStorage`, optional Redis cache |
| DTOs | `dto/*` |
| DB | `src/main/resources/db/migration/` (Flyway), `ddl/*.sql` for tests |

Product design doc (cross-repo): `D:\code\testdata-studio-ui\docs\DATA_STUDIO_DESIGN.md`.  
**Variable user guide:** `D:\code\testdata-studio-ui\docs\VARIABLES.md`.

## Global and environment variables

- **Environment variables** live in the environment JSON document (`StudioEnvironment.variables`).
- **Global variables** are tenant-scoped JSON in table `td_global_variables` (Flyway **V11** + DDL scripts).
- Model: `collection/StudioVariable` (key, value, secret). Normalize via `StudioVariables.normalize(list, scope)`.
- **Secrets:** stored with `value = null`; import rejects raw secret values; export uses `${STUDIO_VAR_<KEY_UPPER>}` via `StudioVariables.forExport`.
- **Run scope:** `RunVariables.of(envValues, globals, envVars, request.secrets)` — precedence: env `values` (incl. baseUrl) → globals → env vars → request secrets (non-blank, declared secret keys only) → extracts mutate the per-run map.
- **Substitution:** `CaseVariables.substitute` resolves `{{name}}`; names starting with `$` use `DynamicVariables` (fresh per occurrence).
- **Scope qualifiers:** `{{global:x}}`, `{{env:x}}`, `{{extract:x}}` read one layer. `RunVariables.of` also stores `global:`/`env:` keys (env values such as baseUrl count as `env:`); `CaseVariables.putExtracted` writes `x` and `extract:x`; chain order and prerequisites use `CaseVariables.producedNames`. Environment export (`EnvironmentExporter.export(spec, stored, globals)`) adds an optional top-level `globals` array for CI; import ignores it.
- **Masking:** `RunVariables.mask(RunTestResult)` before history/response — secrets never echoed in URL, headers, body, assertions, error, extracted map.
- **Endpoints:** `GET/PUT /api/variables/globals` (`VariablesController`); runs accept optional `secrets` on `RunTestRequest` / `RunPlanRequest`; saved run endpoint forwards secrets from body.
- **Validate plan:** seed available names from env + global keys (and declared secret names for warnings).

Key tests: `RunVariablesTest`, `EnvironmentImportTest` (variables round-trip), `VariableRequestJsonTest`.

## Assertions

- Expected body: `TestRunService.expectedBodyCheck` uses `JsonBodyMatcher` with the case's `responseMatch` (`strict` default / `ignoreOrder` / `lenient`; stored normalized, null = strict; column `response_match`, Flyway **V12**). The check's `path` carries the mode label and the first difference.
- JSONPath operators: `JsonPathEvaluator` (`op:containsObject:<json>` / `op:notContainsObject:<json>` compare list items leniently via `JsonBodyMatcher`).
- Typed extracts: a quoted whole-value `"{{x}}"` keeps the extract's JSON type in bodies, expected response and assertion text (`CaseVariables.JSON_TYPE_PREFIX`, `quotedTyped`).
- User guide: `D:\code\testdata-studio-ui\docs\ASSERTIONS.md`; `AssertionsGuideExamplesTest` verifies its examples. api-automation mirrors `JsonBodyMatcher` and the operators.

## Runs and entitlements

- Single case / try: `TestDataStudioService.run(RunTestRequest)`.
- Plan: `runPlan` with shared variable map across ordered chain (`CaseVariables.orderForChain`).
- Prerequisites: `CaseVariables.prerequisites(..., definedNames)` skips names already in run scope.
- Tenant: `TenantContext.tenantId()` for storage scoping.

## Storage migrations

When adding tables/columns, update **both** Flyway `db/migration` and `db/migration_mysql` if applicable, plus `ddl/h2.sql`, `ddl/schema.sql`, `ddl/postgresql.sql`, `ddl/sqlserver.sql` for embedded/test DDL.

## Coding conventions

- Records/DTOs: watch Jackson with extra constructors; verify with tests if adding fields.
- Collection errors: `CollectionFormatException` → 400 via existing handler.
- Match existing service/storage patterns; forward new `StudioStorage` methods through `ForwardingStudioStorage` / Redis wrapper.
- Minimize diff scope; no drive-by refactors. Comments only for non-obvious business rules.
- Do not commit unless the user asks.

## Gson vs Jackson

Environment/collection documents often use Gson (`CollectionDocuments`). REST JSON typically Jackson. Keep both consistent when changing DTO shapes.

## Known flaky test (unrelated to features)

`StudioAuditServiceTest.recordsCaseCreateAndUpdateEvents` can fail when two events share the same `occurred_at` millisecond — ordering is undefined. Fix with tie-breaker in SQL or test delay if touching audit.

## UI follow-up

After API changes, update `D:\code\testdata-studio-ui\src\api.ts`, types, and Guide text as needed; run UI `npm run test` and `npm run check:config` when config keys change.
