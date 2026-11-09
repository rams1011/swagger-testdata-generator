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
| DB | `src/main/resources/db/migration/` and `db/migration_mysql/` (Flyway); tests use the same migrations (`TestSchema.migrate`) |

Product design doc (cross-repo): `D:\code\testdata-studio-ui\docs\DATA_STUDIO_DESIGN.md`.  
**Variable user guide:** `D:\code\testdata-studio-ui\docs\VARIABLES.md`.

## Global, collection and environment variables

- **Environment variables** live in the environment JSON document (`StudioEnvironment.variables`).
- **Global variables** are tenant-scoped JSON in table `td_global_variables`.
- **Collection variables** are one JSON document per collection in `td_collection_variables` (migration **V4**, cascade-deleted with the spec; kept out of `td_spec` so saving them doesn't bump the collection revision). `StudioVariables.fromDocument` / `toDocument` read and write `{"variables": [...]}`. Collection export writes them as the optional top-level `variables` array of `StudioCollection` (secrets → `${STUDIO_VAR_*}`); import normalizes them before writing anything (a raw secret value is refused) and merges them by name (`StudioVariables.merge`). Data rows (below) rank above environment variables.
- Model: `collection/StudioVariable` (key, value, secret). Normalize via `StudioVariables.normalize(list, scope)`.
- **Secrets:** stored with `value = null`; import rejects raw secret values; export uses `${STUDIO_VAR_<KEY_UPPER>}` via `StudioVariables.forExport`.
- **Run scope:** `RunVariables.of(envValues, globals, collectionVars, envVars, request.secrets)` — precedence: env `values` (incl. baseUrl) → globals → collection vars → env vars → request secrets (non-blank, declared secret keys only) → extracts mutate the per-run map.
- **Substitution:** `CaseVariables.substitute` resolves `{{name}}`; names starting with `$` use `DynamicVariables` (fresh per occurrence).
- **Scope qualifiers:** `{{global:x}}`, `{{collection:x}}`, `{{env:x}}`, `{{data:x}}`, `{{extract:x}}` read one layer. `RunVariables.of` also stores `global:`/`collection:`/`env:` keys (env values such as baseUrl count as `env:`); `CaseVariables.putExtracted` writes `x` and `extract:x`; chain order and prerequisites use `CaseVariables.producedNames`. Environment export (`EnvironmentExporter.export(spec, stored, globals)`) adds an optional top-level `globals` array for CI; import ignores it.
- **Masking:** `RunVariables.mask(RunTestResult)` before history/response — secrets never echoed in URL, headers, body, assertions, error, extracted map.
- **Endpoints:** `GET/PUT /api/variables/globals` and `GET/PUT /api/variables/collections/{specId}` (`VariablesController`); runs accept optional `secrets` on `RunTestRequest` / `RunPlanRequest`; saved run endpoint forwards secrets from body.
- **Validate plan:** seed available names from env, collection and global keys (and declared secret names for warnings).

Key tests: `RunVariablesTest`, `EnvironmentImportTest` (variables round-trip), `VariableRequestJsonTest`.

## Assertions

- Expected body: `TestRunService.expectedBodyCheck` uses `JsonBodyMatcher` with the case's `responseMatch` (`strict` default / `ignoreOrder` / `lenient`; stored normalized, null = strict; column `response_match`, Flyway **V12**). The check's `path` carries the mode label and the first difference.
- Number vs text: `JsonBodyMatcher` treats text holding a JSON number as that number in every mode, both directions (global/environment variables are always text); `"007"` and `"true"` stay text.
- JSONPath operators: `JsonPathEvaluator` (`op:containsObject:<json>` / `op:notContainsObject:<json>` compare list items leniently via `JsonBodyMatcher`).
- Typed extracts: a quoted whole-value `"{{x}}"` keeps the extract's JSON type in bodies, expected response and assertion text (`CaseVariables.JSON_TYPE_PREFIX`, `quotedTyped`).
- Response schema: the operation's documented response schema is the oracle (`ResponseSchemaOracle`), applied to runs, saves and definition reloads only when the case expects that response's status (`schemaFor`); a case expecting another status, such as a 404, gets no schema.
- Ignore fields: case field `ignoreFields` (JSONPaths, one per line; column `ignore_fields`, Flyway **V13**), removed from both bodies by `JsonPathEvaluator.withoutPaths` before `JsonBodyMatcher`.
- XPath: `xpath:<expr>=<check>` rows in `jsonPathAssertions` go to `XPathEvaluator` (secure DOM, no namespaces; nodes → value/list, then `JsonPathEvaluator.checkRead`).
- User guide: `D:\code\testdata-studio-ui\docs\ASSERTIONS.md`; `AssertionsGuideExamplesTest` verifies its examples. api-automation mirrors `JsonBodyMatcher` and the operators.

## Drafts of published cases

Editing a published case and saving a draft (`lifecycle: DRAFT` on PUT) never touches `td_test_case`: the request is stored in `td_test_case_draft`, so Run plan, single-case Run, export and api-automation's JDBC read keep the published version. `lifecycle: READY` publishes and clears the draft; `keepDraft: true` changes a setting such as Enabled and leaves it; `DELETE /api/testdata/{id}/draft` discards it; import replaces the case and clears it. Publishing checks `expectedDraftRevision` and that the published version hasn't moved on (`base_revision`), and answers 409 otherwise. `TestCaseView` carries `hasDraft` and `draft`. A file uploaded to a published case waits for a draft to refer to it; export leaves out draft-only files (`CollectionMapper`). Anyone signed in to a workspace can publish.

Every published state is copied to `td_test_case_version` (`JdbcStudioStorage.saveTestCase`, document from `CaseVersionDocument`). `GET /api/testdata/{id}/versions` lists the latest 50, skipping rows where only Enabled or environments changed; `POST .../versions/{versionId}/restore` publishes that content as a new version through `update` (same file binding and audit), ends any draft and keeps Enabled and environments. A version using a file that's no longer stored can't be restored (`missingFiles`). Rows aren't pruned yet.

## Integrity rules (migration V2 and V3)

- **Revisions:** `td_spec`, `td_test_case` and `td_test_case_draft` have a `revision` that each save increases by one. Requests send `expectedRevision` (cases, collections, Restore) and `expectedDraftRevision`; `OptimisticConcurrency.ensureCurrent(current, expected, force)` answers 409 on a mismatch, and the case `UPDATE` repeats the check (`AND revision = ?`), so a save landing in between is refused too. Views carry `revision`; `updated_at` is for display only. Settling a draft after an Enabled change keeps its revision.
- **Environment membership:** `td_test_case_environment (test_case_id, environment_id)`; no rows means every environment. The model keeps names (`StudioTestCase.environments`): `JdbcStudioStorage` resolves them to IDs on save (an unknown name is refused) and back on read, so a rename needs no case writes. Deleting an environment fixes its cases first, then deletes the row. Collection import creates missing environments, empty, by name (`CollectionImporter.ensureEnvironments`). V3 (Java, `db.migration` / `db.migration_mysql`) moved the old `environment_names` JSON into links.
- **Keys and required columns:** a case needs a plan of its collection (deleting the plan deletes its cases); run history keeps rows when a plan or environment goes (reference set to null); `td_spec.tenant_id/name/label` are required. Deliberately not keyed: `td_private_target_allowlist` (configuration can name an environment before it exists) and `td_audit_event` (a log written at startup, too).
- **Checks:** lifecycle, run trigger, outcome, membership roles, parameter location. In `db/migration` (PostgreSQL/H2) write them as `CASE x WHEN 'a' THEN TRUE ... ELSE FALSE END`, never `IN (...)`: H2 2.4 evaluates an `IN` list with the connection that created the check and fails every insert once that connection closes (`MigrationRulesTest`). In `db/migration_mysql` write `x IN (...)`: MySQL rejects the `CASE` form as non-boolean (error 3812).
- **Enabled** is a boolean column; the model and API keep "Y"/"N" (`JdbcStudioStorage.enabledFlag`). Audit lists order by `occurred_at` then `event_seq`.
- **UTC:** `TestdataStudioApplication.useUtc()` sets the JVM zone, so times stored without a zone don't depend on the server's.

## Shared auth and headers

- `collection/SharedSettings` (`auth`, `headers`, `groups` of `Level(auth, headers)`): one document per collection in `td_collection_settings` (migration **V6**, cascade-deleted, kept out of `td_spec`). `GET/PUT /api/variables/collections/{specId}/shared-settings` (`VariablesController`). `normalize`: auth through `CaseAuth.normalize` (secrets must be `{{variable}}`), header names checked, duplicates refused, empty groups dropped.
- `applyTo(case)`: auth is the case's own, else its group's (matched by `testGroup`, any letter case), else the collection's, else the environment run auth; headers stack collection → group → case (case wins, any letter case). `TestDataStudioService.withShared` applies it to the case of a single run (after the environment overlay), to plan cases before `orderForChain` (so shared auth placeholders order the chain), to prerequisites and to Validate.
- Export writes the optional top-level `settings` of `StudioCollection`; import normalizes first and merges (`SharedSettings.merge`: collection auth / headers when the file has them, groups by name). api-automation applies the same rules (`utils/SharedSettings`, from the collection JSON or `td_collection_settings`).

## Data-driven runs

- A case may hold **data rows** (`StudioTestCase.dataRows`, normalized JSON array text; column `td_test_case.data_rows`, migration **V5**). REST: `SaveTestDataRequest.dataRows` (list of objects; null keeps, empty list removes) and `TestCaseView.dataRows`; drafts, versions (`CaseVersionDocument`), duplicate and the collection case `data` (Gson `JsonElement`, so numbers stay numbers) carry them.
- `service/DataRows`: `normalize` (≤1000 rows, ≤100 columns, ≤2M chars; column names like variables; special columns `$name` label, `$status` expected status 100–599, `$body` / `$expected` objects of JSONPath → value, `$remove` a list of JSONPaths left out of the request body), `parse`, `variables(row)` (`x` and `data:x`, plus `JSON_TYPE_PREFIX` keys for non-text values so a quoted `"{{x}}"` keeps its type), `apply(case, row)` (Jayway with the Jackson provider: set an existing path, add a child to an existing parent, or append at a list's next index; a missing parent or a gap in a list fails the row).
- `service/DataRowRun` sends each row with the run's variables plus the row's (above environment variables, below extracts). Extracts carry on to later rows and cases; the row's own values don't. The result sums the rows up: one `data row` check per row, `error` "2 of 5 data rows failed: …", and `RunTestResult.iterations` (each with `rowLabel`). `TestDataStudioService.executeCase` uses it for single runs, prerequisites and Run plan (counted once per case; history stores the summary). `RunTestRequest.dataRow` (1-based) runs one row, for Send.
- `CaseVariables.placeholderNames` leaves out the case's own data columns (and reads placeholders inside `$body` / `$expected`), so chain order, prerequisites and Validate don't look for them elsewhere.

## Runs and entitlements

- Single case / try: `TestDataStudioService.run(RunTestRequest)`.
- Plan: `runPlan` with shared variable map across ordered chain (`CaseVariables.orderForChain`). `RunPlanRequest.caseIds` (null = all) runs only those cases plus the earlier cases they need extracted values from (`chosenWithPrerequisites`, using `CaseVariables.prerequisites`); the run is recorded as a plan run of just those cases. `RunPlanRequest.delayMs` (≤60000) and `retries` (≤5) go to `PlanRun.Options`; a retried case gets an `attempts` check.
- Connection settings: `StudioEnvironment.network` (`collection/StudioEnvironmentNetwork`: followRedirects, verifySsl, caCertificate PEM, proxyUrl, requestTimeoutMs; normalized, null = defaults) on create/update (`SaveEnvironmentRequest.network`, null keeps) and in the environment file. `TestRunService.execute(..., network)`: clients from `NetworkClients` (trust-all or default+extra CA trust manager, proxy; cached), redirects followed by `followRedirects` with an `OutboundUrlPolicy` check per hop (proxy URL checked too), timeout = env timeout or case max + 5 s, capped by `testdata.run.max-request-timeout-ms` (300000, env `TESTDATA_RUN_MAX_REQUEST_TIMEOUT_MS`); response display cut at `testdata.run.max-response-chars` (1,000,000).
- Outbound policy: `OutboundUrlPolicy` resolves a host once and returns the validated address; `NetworkClients` (Apache HttpClient) connects to it while Host, SNI and the certificate check use the name. Private targets need `testdata.run.private-target-allowlist.<environmentId>` (`host:port`, `cidr:port`) or the deprecated `allow-private-networks`; metadata, link-local, the server's own ports and `blocked-infrastructure-ports` can't be allowed. `PrivateTargetAllowlistAuditor` audits config changes at startup (`td_private_target_allowlist`, Flyway **V15**). Spec import (`SpecCatalogService.readAllowedLocation`) is pinned the same way, under `Scope.SPEC_IMPORT`: no environment's allowlist applies, so a private spec URL still needs the deprecated flag. `OpenApiSpecLoader.rejectExternalReferences` scans JSON/YAML tokens for external `$ref`s and discriminator mappings, because the parser would fetch them. `CiResponseChecks.schema` validates with no URI loaders, so a response schema can only reference itself.
- OAuth: `OAuthTokenCache` (in memory, SHA-256 key incl. secrets and `RunAuth.scope`, `expires_in` − 30 s, default 5 min); a 401 invalidates.
- Case auth: `StudioTestCase.auth` (`RunAuth`; null = run auth, `NONE` = none), column `case_auth` (Flyway **V14**), exported as collection `auth`. `CaseAuth.normalize` requires `{{variable}}` in secret fields; `CaseVariables.bind` resolves it and `placeholderNames` includes it.
- Request bodies keep JSON `null` and whole numbers end to end: `JsonMaps` (storage) and `CollectionDocuments` (import) read numbers with `ToNumberPolicy.LONG_OR_DOUBLE` and store with `serializeNulls`, and `TestRunService` sends with `serializeNulls`. `JsonSchemaSupport.forValidation` also turns whole decimals back into integers (`minItems: 1.0` → `1`) for schemas stored before that. Collection export keeps them too: `StudioCollectionCase.body` uses `@JsonAdapter(KeepNullsJson.class)`, so `"middleName": null` reaches the file while the rest of the file still leaves empty fields out; api-automation reads the body with `serializeNulls` (`CollectionCaseMapper`, `StudioJdbcRowMapper`, `DataRowExpander`). An export made before this lacks the nulls and must be redone.
- `RunTestResult.requestBody` holds the body as sent (masked by `RunVariables.mask`) for the UI's Copy as cURL.
- Blank query, header and cookie values are optional parameters left unset and are never sent (`HttpPayloadSupport.appendQuery`, `TestRunService.filled`); the UI also leaves them out when saving (`casePayload.filledValues`). `collection/HeaderRules` refuses header names that are not tokens, values with line breaks, and headers the HTTP client sets itself (Host, Connection, Content-Length, Expect, Upgrade): on case save (400), in shared settings, and at run time, where auth and header problems fail that one case instead of throwing out of `execute` (which would end a Run plan). api-automation mirrors the blank rule and Studio's exact content type (`client/SendableValues`, `RestClient.applyContentType`; `utils/JoinedValues` escapes `|` in the joined name/value text). Path parameter values are encoded one by one after their variables are filled in (`CaseVariables.endpoint`, `HttpPayloadSupport.encodePathValue`: only letters, digits and `-._~` stay, an existing `%XX` stays), so `a/b` is `a%2Fb`; a `{name}` left without a value fails the case with "Path parameter 'name' has no value". api-automation builds the path the same way from the case's `path` and `pathParams` (`utils/PathValues`, JDBC reads `param_in = path` too) and turns RestAssured's own URL encoding off, encoding query names and values itself.
- Prerequisites: `CaseVariables.prerequisites(..., definedNames)` skips names already in run scope, and never picks a draft: a draft's extracts aren't available to other cases until it is published.
- `{{baseUrl}}`: `TestDataStudioService.withBaseUrlFallback` adds the spec's base URL when the environment has none (or none is selected). Collection import stores cases on the collection's base URL as `{{baseUrl}}` (`CollectionMapper.resolveStoredBaseUrl`).
- Last run per case: `GET /api/runs/latest-by-case?specId&planId&environmentId` (`RunHistoryService.latestByCase`, storage `latestCaseRuns`). Home cards: `GET /api/specs/activity?ids=` (`TestDataStudioService.specActivity`: `countTestCases`, environment count, newest run).
- Tenant: `TenantContext.tenantId()` for storage scoping.

## Storage migrations

The migrations are the only schema definition (fresh `V1__studio.sql` baseline, 2026-11-07; `R__default_workspaces.sql` seeds the Local and SaaS workspaces). To change it: add the next `V2__…` to **both** `db/migration` (PostgreSQL/H2) and `db/migration_mysql` (MySQL types: `LONGTEXT`, `DATETIME(3)`, `LONGBLOB`), then update `src/test/resources/schema-snapshot.txt` (`SchemaSnapshotTest` writes the H2 result to `target/schema-snapshot.h2.txt`; the `-Pintegration` job holds PostgreSQL and MySQL to the same file). Never edit an applied migration. Tests build their database with `TestSchema.migrate(dataSource)`. `baseline-on-migrate` is off. Avoid MySQL reserved words as column names (`td_usage_month.usage_month`, not `year_month`).

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
