---
name: tds-postman-parity-review
description: Use when asked to assess Test Data Studio (D:\code) against Postman for REST API testing: find strengths, gaps, and a prioritized parity roadmap.
---

# Test Data Studio vs Postman — parity review

Repeatable procedure for analyzing Test Data Studio (TDS) and reporting strengths, gaps and a roadmap relative to Postman for REST API testing. Always verify against the **current code** — the baseline at the bottom is a starting point, not the answer.

## Repos (on the user's computer, D:\code)

| Repo | Role |
|------|------|
| `swagger-testdata-generator/testdata-generator-api` | Spring Boot Studio API — run engine, variables, auth, import/export |
| `swagger-testdata-generator/testdata-generator-core` | OpenAPI parse, schema-based case generation, offline CLI that writes collection and environment JSON |
| `testdata-studio-ui` | React/Vite UI |
| `api-automation` | TestNG + RestAssured CI runner (Newman equivalent) |

Read first: both `CLAUDE.md` files, `testdata-studio-ui/docs/DATA_STUDIO_DESIGN.md`, `docs/VARIABLES.md`, `api-automation/README.md`.

## Working rules

- Avoid one huge recursive grep over D:\code (it times out on node_modules and target). Grep `src/main`, `src`, `docs` and the READMEs per repo instead.
- Cite evidence (file + class/function) for every gap or strength claim. If you only read code and did not run the app, say so.
- Watch for false matches: "Postman-style" appears in comments (`DynamicVariables`, `KeyValueRows.jsx`), and `KeyStore` in `NetworkClients` is for trusting a CA, not client certificates.

## Building and testing (if the review leads to code changes)

Both repos build and test locally on Windows (PowerShell; Git Bash is also available). Follow each repo's `CLAUDE.md`.

- API: `mvn -o -f D:/code/swagger-testdata-generator/pom.xml -pl testdata-generator-api -am test`. Pass `"-DargLine=-Djdk.net.unixdomain.tmpdir=<repo>/testdata-generator-api/target"` if the JVM can't create its socket temp files. `StudioAuditServiceTest` is known to be flaky.
- UI: `npm run lint`, `npm run typecheck`, `npm run test` (Vitest), `npm run build` in `testdata-studio-ui`.
- Running the app: never point a test API at the real H2 file in `testdata-generator-api/studio-output` (the default profile migrates it, and `testdata.h2.in-memory` doesn't stop that). Set `TESTDATA_H2_JDBC_URL` and `TESTDATA_H2_FILE` to a new file under `target`, use spare ports (`SERVER_PORT`, `MANAGEMENT_PORT`, Vite `--port` with `API_PROXY_TARGET`), and leave the user's own servers alone. `scripts/capture-guide-screenshots.mjs` in the UI repo shows the full setup.
- Preserve CRLF line endings. Don't commit unless the user asks. Remove temporary databases and servers when done.

## Key files to inspect (API, package `.../studio/`)

- `service/TestRunService.java` — HTTP client, timeouts, redirects, TLS, auth application, OAuth token fetch, body types, assertion evaluation, response truncation.
- `service/PlanRun.java` + `TestDataStudioService.runPlan` — plan run semantics (stop on failure vs `continueOnFailure`, dependency skips, ordering).
- `service/JsonPathEvaluator.java` (Jayway) — path syntax, many-match semantics, assertion operators (`op:*`); mirrored in `api-automation/.../validation/JsonPathEvaluator.java`.
- `service/HeaderAssertions.java`, `CiResponseChecks.java`, `ResponseSchemaOracle.java` — header/cookie/schema/download checks.
- `service/CaseVariables.java`, `RunVariables.java`, `DynamicVariables.java` — `{{var}}` substitution, precedence, extracts, chain ordering, dynamic `$` vars.
- `service/OutboundUrlPolicy.java`, `NetworkClients.java` — SSRF guard, per-environment private-target allowlist (`testdata.run.private-target-allowlist.<environmentId>`), redirects, TLS, proxy.
- `collection/StudioEnvironmentNetwork.java` — environment Connection settings. `service/CaseVersionDocument.java` + `TestDataStudioService.caseVersions/restoreCaseVersion` — version history and Restore.
- `dto/RunAuth.java` — auth types. `dto/RunTestRequest.java`, `RunPlanRequest.java` — run options.
- `collection/CollectionImporter.java`, `CollectionPackage` — import formats.
- UI: `src/schema.js` (assertion parse/serialize, `JSON_CHECK_KINDS`), `components/JsonPathAssertionsPicker.jsx`, `pages/CasesPage.jsx` (Run plan, Continue on failure), `src/curlImport.js`/`parseCurl.js`, `contentTypes.js`, `components/RunResultPanel.jsx`, `pages/ExportPage.jsx`, `pages/HistoryPage.jsx`, `components/RunComparePanel.jsx`.

## Postman capability checklist (check each one)

1. Request building: methods, params, headers, cookies, body types (raw JSON/XML/text, form-urlencoded, multipart/files, binary, GraphQL)
2. Variables: scopes (global/collection/env/local/data), precedence, secrets, dynamic `$` variables
3. Scripting: pre-request and test scripts, `pm.*` API, sandboxed JS
4. Assertions: status, time, headers/cookies, JSONPath (wildcards, filters, recursive, length), operators (contains, ne, gt/lt, type, length), schema validation, partial/ignore-field body compare, XPath
5. Chaining: extracting values (body/header/cookie), automatic vs manual ordering
6. Collection runner: iterations, CSV/JSON data files, continue on failure, delays, retries, run a subset (folders/tags), parallelism
7. Organization: folders, tags, auth inheritance (collection → folder → request)
8. Auth: Basic, Bearer, API key, OAuth2 (client creds, password, auth code, PKCE, token caching/refresh), Digest, NTLM, AWS SigV4, Hawk, mTLS client certs
9. Network/TLS: SSL verification toggle, custom CA, proxy, follow redirects, configurable timeout, response size limits, reaching localhost/private networks (desktop agent)
10. Import/export: Postman v2.1, Insomnia, HAR, OpenAPI, cURL import, code snippets / Copy as cURL
11. CI: CLI runner (Newman equivalent), report formats (JUnit XML, HTML), exit codes
12. Collaboration: workspaces, roles, history, audit, conflict handling, version compare
13. Extras: mock servers, monitors/scheduled runs, protocols (GraphQL, WebSocket, gRPC, SSE)

## Output format

1. One-paragraph verdict.
2. **Strengths** — bullets, where TDS is equal to or better than Postman, each with evidence.
3. **Gaps** — table: `# | Gap | Evidence | Impact`, ordered by impact on REST testing.
4. **Recommended parity roadmap** — numbered, starting with quick wins and ending with high-risk items (scripting last because of server-side sandbox risk).
5. If a previous baseline exists, add a **Changes since last review** section (closed gaps, new gaps).

Deliver in chat for a quick verdict; if the user wants something to share or keep, make a living doc and/or save it to the TestDataStudio project.

## Change log

- **2026-11-04 (review):** initial baseline, 16 gaps.
- **2026-11-04 (implemented):** closed former gaps 3–5 — Run plan `continueOnFailure` (UI checkbox, dependent cases skipped with reason); Jayway JSONPath in Studio API and api-automation (wildcards, deep scan, filters, slices, negative indexes, functions; many-match = list); new operators `ne`, `contains`, `notContains`, `gt`, `gte`, `lt`, `lte`, `type`, `length`, `notExists`, `empty`; filter-safe `=`/`|` parsing in API and UI; api-automation extracts switched from RestAssured GPath to the shared evaluator. Not yet confirmed by a local `mvn clean install` / `npm run test` on the user's machine.

- **2026-11-04/05 (implemented):** extract `||` fix (gap 15 closed); variable picker, scope qualifiers `{{global:/env:/extract:}}`, dynamic values in api-automation; Excel removed (collection JSON only); typed extracts (quoted `"{{id}}"` keeps numbers); Body match `strict`/`ignoreOrder`/`lenient` with first-difference messages; `op:containsObject`/`op:notContainsObject`; Ignore fields (JSONPaths removed before body compare); XPath assertions (`xpath:` paths); Run plan Delay and Retries (Attempts check); Copy as cURL/PowerShell/fetch/Python on run results (`RunTestResult.requestBody`); api-automation `--retries`, `--delay-ms`, JUnit XML (`--junit`, default `target/reports/junit-report.xml`) and filter-safe assertion parsing; `docs/ASSERTIONS.md` guide verified by `AssertionsGuideExamplesTest`. Gaps 3, 12, 13 (except parallel runs) and 15 closed.

- **2026-11-05 (implemented):** environment Connection settings (follow redirects with per-hop SSRF check, Verify SSL off, extra CA PEM, proxy, request timeout up to 5 min, 1M-char result bodies) in Studio and api-automation (from the environment file); OAuth token cache with expiry and 401 invalidation, OAuth scope (Studio and api-automation, which now renews tokens during a suite); case-level auth (NONE/BASIC/BEARER/API_KEY/OAUTH2, secrets only as `{{variables}}`). Gaps 6 (except proxy auth), 8 and the token-per-request part of 5 closed.

- **2026-11-05 to 2026-11-07 (implemented, not Postman gaps):** drafts of published cases (`td_test_case_draft`, Flyway V16), so a saved draft never changes what runs or exports until it's published; a draft's extracts aren't available to other cases; every published version kept in `td_test_case_version`, with **Version history** and **Restore** in the case editor. UI: show or hide the left navigation; the case editor's Request / Expected response / Result tabs. Verified with `mvn test`, `npm run test` and a throwaway running app.

- **2026-11-07 (implemented):** gap 1 closed for Studio: Run plan takes `caseIds` and adds the cases they need values from; the Cases page has a Group filter and **Run selected** (run a group by filtering it and ticking the header box). Folders are still not a concept.

- **2026-11-07/08 (implemented, robustness, no parity gaps closed):** database stage 1: one fresh Flyway baseline per database type (PostgreSQL/H2 and MySQL), hand-written DDL files removed, tests build their database with the real migrations (`TestSchema`), a schema snapshot checked on H2 in every run and on PostgreSQL and MySQL in the `-Pintegration` job, `baseline-on-migrate` off; MySQL fixes (missing migration, `LONGTEXT`, `DATETIME(3)`, `year_month` renamed `usage_month`). Stage 2 (V2, plus V3 in Java): case environments linked by ID, required plans and collection names, foreign keys from run history, revision numbers for conflict checks, an audit sequence, Enabled as a boolean, value checks (`CASE` form on PostgreSQL/H2 because of an H2 2.4 `IN` bug, `IN` on MySQL), UTC. Fixed along the way: the case editor silently overwrote other people's saves (it now shows the Reload / Overwrite dialog); saving an environment re-saved the collection and showed a false "API definition changed" message; the editor lost unsaved edits when the collection's base URL changed. UI: Request / Expected response / Result tabs in the case editor. Stage 3 (one case document) is next in the database plan and would make data-driven runs (gap 2) cheaper.

- **2026-11-08 (implemented):** gap 7 closed: shared auth and headers. The collection and each case group can set auth (or No auth) and headers that cases without their own inherit: case → group → collection → environment for auth, collection → group → case for headers (`td_collection_settings`, migration V6). Edited from Environment → **Shared auth and headers…**; the case editor shows what it inherits. Exported as `settings`, merged on import, applied by api-automation. Also: Cases table cut to five columns with **Extracts** / **Variables** chips and source popovers.

- **2026-11-08 (implemented):** gap 2 (data-driven runs) and the rest of gap 6 closed: a case holds data rows (CSV or JSON, up to 1,000; `td_test_case.data_rows`, migration V5) and Run, Run plan and CI send it once per row. Columns are `{{x}}` / `{{data:x}}` above environment variables; `$name`, `$status`, `$body` and `$expected` (JSONPath patches) keep large bodies in the case. Results list every row; the CI runner makes one test per row. The case editor also got a Group field.

- **2026-11-08 (implemented):** gap 6 partly closed: collection variables. One document per collection (`td_collection_variables`, migration V4), edited from Environment → **Collection variables…**, with secrets entered in the browser only; they rank between global and environment variables, `{{collection:x}}` reads them alone, and they are in autocomplete, chips and Validate. Collection export carries them as a top-level `variables` array (secrets as `${STUDIO_VAR_*}`), import merges them by name, and api-automation reads them from the collection JSON or over JDBC. Data variables wait for data-driven runs.

- **2026-11-08/09 (implemented, correctness, no parity gaps closed):** collection export keeps a JSON `null` in request bodies (`KeepNullsJson`), and api-automation reads it back. Blank query, header and cookie values are optional parameters left unset and are never sent (Studio, api-automation, and the UI no longer saves them). api-automation now sends what Studio sends: the exact content type (form bodies as `application/x-www-form-urlencoded`, JSON when none is set), `|` and spaces inside values, every value of a repeated parameter, and parameters in order. `HeaderRules` (token names, no line breaks, not Host, Connection, Content-Length, Expect or Upgrade) applies on save, in shared settings and at run time, where a bad header or a missing credential fails that case instead of ending the Run plan. Path values are encoded one by one in both runners (`a/b` → `a%2Fb`, no double encoding), and a missing one fails the case by name. Verified with `mvn clean install` (API), the api-automation unit tests, `npm run test`, and the demo collection through api-automation.

- **2026-11-07 (review):** re-verified the open gaps against the code. The baseline below replaces the 2026-11-04 one.

## Baseline findings (re-verified 2026-11-07, updated 2026-11-08 — re-verify each one)

**Strengths:** OpenAPI-driven case authoring with response-schema validation; `{{var}}` global, collection and environment variables plus per-case data rows (CSV/JSON, one run per row) with precedence, scope qualifiers (`{{global:}}`, `{{collection:}}`, `{{env:}}`, `{{data:}}`, `{{extract:}}`) and `$` dynamic values; shared auth and headers at collection and group level; secrets stored as names only, masked in history, exported as `${STUDIO_VAR_*}`; extracts (JSONPath/header/cookie) with typed values and automatic producer-before-consumer ordering; JSON/XML/form/multipart/binary bodies; Basic, Bearer, API key and OAuth2 (client credentials, password) at environment or case level, OAuth tokens cached with 401 renewal; assertions for status, time, headers/cookies, schema, body match (strict / ignore order / lenient, ignore fields), full Jayway JSONPath with operators, XPath; the same evaluator in Studio and api-automation; Run plan with continue on failure, delay, retries and dependency-aware skips; Connection settings (redirects with per-hop SSRF check, Verify SSL off, extra CA, proxy, timeout up to 5 min); Copy as cURL/PowerShell/fetch/Python; api-automation CLI with parallel threads, `--group`, retries, delay, JUnit XML and Extent HTML; tenancy, roles, audit, conflict detection, drafts of published cases, version history with Restore, run history compare, Validate plan.

**Gaps (ordered by impact on REST testing):**
1. Closed 2026-11-07: Studio runs a subset (`RunPlanRequest.caseIds`, Group filter and **Run selected** on the Cases page). Still no folders.
2. Closed 2026-11-08: data-driven runs (data rows per case, once per CSV/JSON row in Studio and CI).
3. No Postman v2.1, Insomnia or HAR import (Studio collection format and cURL only).
4. Auth: no OAuth authorization code / PKCE (`TestRunService` rejects `authorization_code` and `implicit`), Digest, NTLM, AWS SigV4, Hawk or client certificates (mTLS).
5. No pre-request or test scripting.
6. Closed 2026-11-08: collection variables, and data variables through data rows (see change log).
7. Closed 2026-11-08: shared auth and headers at collection and group level (groups act as one-level folders).
8. Partly closed: private targets need a per-environment allowlist on the server; there's no local agent for localhost and internal APIs.
9. No parallel runs in Studio's Run plan (api-automation has `--threads`).
10. Partly closed: proxy is supported but not proxy credentials.
11. No mock servers; no scheduled runs or monitors.
12. REST only: no GraphQL body type, WebSocket, gRPC or SSE.

**Roadmap (remaining):** (1) ~~run a subset by group or selection~~ done → (2) ~~data-driven iterations~~ done → (3) Postman v2.1 import → (4) client certificates and OAuth authorization code with PKCE → (5) ~~collection variables~~ done, ~~auth inheritance~~ done → (6) parallel Run plan, proxy credentials → (7) local agent for private targets → (8) sandboxed scripting (last, because of server-side sandbox risk) → schedules and mocks as needed.
