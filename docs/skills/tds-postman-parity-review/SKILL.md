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
| `swagger-testdata-generator/testdata-generator-core` | OpenAPI parse, Excel generation, CLI |
| `testdata-studio-ui` | React/Vite UI |
| `api-automation` | TestNG + RestAssured CI runner (Newman equivalent) |

Read first: both `CLAUDE.md` files, `testdata-studio-ui/docs/DATA_STUDIO_DESIGN.md`, `docs/VARIABLES.md`, `api-automation/README.md`.

## Working rules

- Work on the user's computer with device_bash; do not stage source files to the cloud.
- Avoid one huge recursive grep over D:\code (it times out — node_modules/target). Instead build a file list of `src/main`, `src`, `docs`, READMEs (*.java, *.js, *.jsx, *.ts, *.md), concatenate into `/tmp/all.txt`, and grep that.
- Cite evidence (file + class/function) for every gap or strength claim. If you only read code and did not run the app, say so.

## Building and testing (if the review leads to code changes)

- The device shell has Java 11 only, no Maven and no network; the cloud sandbox has Java 21 + Maven but Maven Central and npm are blocked.
- Working approach: request read access to `~/.m2`; take the exact test classpath from `target/surefire-reports/TEST-*.xml` (`surefire.test.class.path`), tar only those jars, stage them, and compile/run with `javac` + the JUnit Platform launcher (Studio) or `org.testng.TestNG -testclass` (api-automation).
- Preserve CRLF line endings; apply changes on the device with `git apply` and verify checksums. `git apply` warns "unable to unlink" without delete permission but still writes the files.
- UI: `node_modules` on D:\code is Windows-native, so vitest can't run in the device VM; ESLint does. Ask the user to run `npm run test` and `mvn clean install` locally.
- Clean up temporary files you create in D:\code (ask for delete permission once).

## Key files to inspect (API, package `.../studio/`)

- `service/TestRunService.java` — HTTP client, timeouts, redirects, TLS, auth application, OAuth token fetch, body types, assertion evaluation, response truncation.
- `service/PlanRun.java` + `TestDataStudioService.runPlan` — plan run semantics (stop on failure vs `continueOnFailure`, dependency skips, ordering).
- `service/JsonPathEvaluator.java` (Jayway) — path syntax, many-match semantics, assertion operators (`op:*`); mirrored in `api-automation/.../validation/JsonPathEvaluator.java`.
- `service/HeaderAssertions.java`, `CiResponseChecks.java`, `ResponseSchemaOracle.java` — header/cookie/schema/download checks.
- `service/CaseVariables.java`, `RunVariables.java`, `DynamicVariables.java` — `{{var}}` substitution, precedence, extracts, chain ordering, dynamic `$` vars.
- `service/OutboundUrlPolicy.java` + `config/StudioProperties` (`run.allowPrivateNetworks`, allow-hosts) — SSRF guard / reachability of private targets.
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

## Baseline findings (updated 2026-11-04 — re-verify each one)

**Strengths:** OpenAPI-driven case authoring plus response-schema validation; `{{var}}` globals/env with precedence and 16 `$` dynamic vars; secrets stored as names only, masked in history, exported as `${STUDIO_VAR_*}`; extracts (JSONPath/header/cookie) with automatic producer-before-consumer ordering, single filter match unwrapped; JSON/XML/form/multipart/binary bodies; Basic/Bearer/API key/OAuth2 client-credentials and password grant; assertions for status, time, headers/cookies, full body, schema, and JSONPath with full Jayway syntax and operators equals/ne/exists/notExists/nonempty/empty/contains/notContains/gt/gte/lt/lte/range/matches/type/length (every-match semantics for many-match paths); Studio and api-automation share the same evaluator; Run plan with optional continue on failure and dependency-aware skips; versioned collection/env export, Excel, JDBC; api-automation TestNG CLI with parallel threads and Extent HTML; tenancy, LDAP, audit, conflict detection, run history compare, Validate plan; SSRF guard, no redirects, host allow list.

**Gaps:**
1. No pre-request or test scripting.
2. No data-driven iterations (CSV/JSON).
3. Body compare is exact match only (no ignore-fields / partial match); no XPath assertions for XML.
4. No Postman/Insomnia/HAR import (only the Studio collection format and cURL).
5. No OAuth auth-code/PKCE, Digest, NTLM, SigV4, Hawk or mTLS; OAuth token fetched on every request.
6. No SSL-skip, custom CA, proxy or follow-redirect options; timeout capped at 60s; response truncated at 100k chars.
7. Requests run from the server; private networks blocked by default (`allowPrivateNetworks=false`), no local agent.
8. Auth only at the environment level; no per-case override or inheritance.
9. No folders/tags inside a plan; can't run a subset from the UI.
10. No mock servers. 11. No scheduled runs/monitors. 12. No Copy as cURL / code snippets.
13. No delay/retry/parallel options in Run plan; no JUnit XML.
14. REST/SOAP-over-HTTP only (no GraphQL, WebSocket, gRPC, SSE).
15. Extract rules still split on every `|` after the `=`, so a filter source using `||` breaks (assertions are safe).

**Roadmap (remaining):** (1) data-driven iterations, folders/tags with run-a-subset → (2) ignore-fields body compare, XPath → (3) Postman v2.1 import → (4) auth and TLS/proxy/redirect options, OAuth token caching, private-target runner → (5) sandboxed scripting → (6) Copy as cURL, JUnit XML, Run plan delay/retry, schedules, mocks.
