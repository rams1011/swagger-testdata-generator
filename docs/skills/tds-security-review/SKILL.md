---
name: tds-security-review
description: Use when asked whether Test Data Studio (D:\code) is safe from attackers, to find security loopholes, or to decide on and fix the open security findings listed here.
---

# Test Data Studio — security review

Repeatable procedure for checking Test Data Studio (TDS) for security loopholes, plus the open findings from the last review so the user can decide what to fix. Always re-check a finding against the **current code** before acting on it — the list at the bottom is a starting point, not the answer.

## Repos (on the user's computer, D:\code)

| Repo | Role |
|------|------|
| `swagger-testdata-generator/testdata-generator-api` | Spring Boot Studio API: sign-in, workspaces, run engine, import/export |
| `testdata-studio-ui` | React/Vite UI, served by nginx in production (`nginx/default.conf.template`) |
| `api-automation` | TestNG + RestAssured CI runner, writes Extent HTML and JUnit XML reports |

Read first: both `CLAUDE.md` files and `api-automation/README.md`.

## Working rules

- Never run tests against the user's own servers (API on 8083, UI on 5173) or the real H2 file in `testdata-generator-api/studio-output`. Start a throwaway API: `TESTDATA_H2_FILE` and `TESTDATA_H2_JDBC_URL` pointing at a new folder under `target`, spare ports (`SERVER_PORT=18085`, `MANAGEMENT_PORT=18086`), and confirm the database URL in the log. Stop it and delete the folder when done.
- Editions: `TESTDATA_EDITION=local` has no sign-in; `saas` has sign-in, self-signup and a writable trial, and starts on the `local` profile with H2 (use it for live sign-in and isolation tests); `datacenter` refuses the `local` profile and H2, so it can't be started that way.
- Test accounts: register them on the throwaway server only, with random passwords the script generates and never prints. Never use or repeat a real password.
- Cite evidence (file + class/method, or the live result) for every claim. Say which findings were tested live and which were only read in code.
- Preserve CRLF line endings. Don't commit unless the user asks.

## Checklist (check each one)

1. **Sign-in bypass:** `tenant/TenantFilter.java` matches the raw `getRequestURI()`. Probe with `curl --path-as-is`: `/actuator/..;/api/specs`, `/api/specs;x`, `/API/specs`, `/api//specs`, `%2e%2e`, `/favicon.ico/..;/…`, `/error/..;/…`, `/h2-console/..;/…`. Expect 401/403/404. Note `TenantContext.tenantId()` falls back to the Local workspace when no scope is set and SaaS is off, so any bypass would reach real data.
2. **Workspace isolation:** with two throwaway accounts, account B reads, writes, deletes and exports account A's collection, variables, shared settings, environments, plans, runs and audit, with and without `X-Workspace-Id: <A's workspace>`. Expect 404/403, or empty lists.
3. **Sign-in throttling:** wrong passwords for one account (locks after about 4), across many accounts from one address (blocked after about 20), and a faked `X-Forwarded-For` (must be ignored; `AuthController` uses `getRemoteAddr`).
4. **Sessions and accounts:** tokens from `SecureRandom`, stored as SHA-256 hashes (`AuthService.hashToken`); idle and absolute expiry; no default admin password (`testdata.saas.admin-password`); self-signup only in SaaS (`AuthController.requireBilling`).
5. **Configuration defaults:** `application.yml` and `application-*.yml` — bind address, H2 console, `allow-private-networks`, CORS origins, actuator exposure on the management port, edition and profile combinations (`DatacenterConfigurationValidator`, `HostingProfileValidator`).
6. **SSRF:** `service/OutboundUrlPolicy.java`, `NetworkClients.java` (private targets, redirects per hop, metadata addresses, DNS pinning). Covered by `OutboundPrivateTargetPolicyTest`, `OutboundUrlPolicyTest` and `PinnedConnectionTest`.
7. **Resource limits:** response size read by the run engine, zip import, upload size (`spring.servlet.multipart`), data rows (`DataRows`), regex checks (`op:matches`), disk use of saved downloads.
8. **Parsers:** XML (`XmlPayloadSupport`, `XPathEvaluator`: DOCTYPE refused), zip paths (`CollectionPackage`), file names (`RequestFileSupport.sanitizeFileName`), OpenAPI external `$ref`s (`OpenApiSpecLoader.rejectExternalReferences`).
9. **UI:** `dangerouslySetInnerHTML`, `innerHTML`, `eval`, iframes; where the session token is kept (`src/session.js`, sessionStorage); nginx headers (CSP, nosniff, Referrer-Policy, frame-ancestors).
10. **CI runner:** HTML escaping in `reports/ExtentLogger.java` and Extent's templates (`com/aventstack/extentreports/markup/codeblock*.ftl` inside the jar); secrets in `config/automation.properties`; Verify SSL off.
11. **Not yet covered:** live SSRF bypass attempts, the OIDC and GitHub sign-in flows, and a dependency vulnerability scan (for example OWASP dependency-check on both Maven projects and `npm audit` in the UI).

## Output format

1. One-paragraph verdict.
2. **What held up** — bullets, each with evidence (live or code).
3. **Loopholes** — table: `# | Loophole | Who can use it | Impact`, most serious first.
4. **Suggested fixes** — numbered, matching the table.
5. **Not covered** — what wasn't tested.
6. If findings below exist, a **Changes since last review** section (fixed, still open, new).

## Change log

- **2026-11-10 (review):** first review. Sign-in bypass, workspace isolation, throttling, XXE and zip paths tested live on a throwaway SaaS server and held up. Nine loopholes found (below). Nothing fixed yet; the user wants to decide later.

## Open findings (2026-11-10 — re-check each one before fixing)

**Held up:** no sign-in bypass (17 path tricks refused); workspace isolation for reads, writes, deletes and exports, with or without another workspace's header; per-account lockout after 4 failures and per-address block after about 20, `X-Forwarded-For` ignored; random hashed session tokens; no default admin password; self-signup SaaS only; DOCTYPE refused in XML; `..` refused in zip paths; download names cleaned; Datacenter refuses H2 and the H2 console; nginx sends a strict CSP; the UI renders no raw HTML.

**Loopholes (most serious first):**

| # | Loophole | Evidence | Who can use it | Impact | Suggested fix |
|---|---|---|---|---|---|
| 1 | Run engine reads a whole response into memory with no size cap, and gzip is unpacked automatically | `service/NetworkClients.java` (`EntityUtils.toByteArray`), Apache HttpClient content decompression | Any signed-in user, pointing a case at their own server | A small gzip bomb exhausts memory and takes Studio down for every workspace. High in SaaS | Stop reading after `testdata.run.max-response-chars` plus a margin and fail with "response too large"; cap decompressed size too |
| 2 | Script injection into api-automation's HTML report | `reports/ExtentLogger.java`; Extent 5.1.1 `codeblock.json.ftl` puts the body raw in `<script>`, `codeblock.ftl` raw in `<textarea>`; `test.info(String)` isn't escaped | Whoever controls a response from the API under test | Script runs when the report is opened; if CI hosts reports (Jenkins HTML publisher) it acts with the viewer's CI session. High | HTML-escape everything written to the report; use the plain code block with escaped text instead of the JSON one |
| 3 | Local edition has no sign-in and listens on all interfaces; local profile allows private-network runs; no Host-header check (DNS rebinding) | Live: 8083/8084 listen on `::`; `application-local.yml` (`allow-private-networks: true`); `StudioProperties.isAuthEnabled` false for LOCAL | Anyone on the same network, or a web page via DNS rebinding | Full control of Studio and a proxy into the internal network. Medium | Default `server.address=127.0.0.1` for the local edition; reject requests whose Host isn't localhost; private networks off unless asked |
| 4 | SaaS edition starts on the `local` profile, which opens private networks to every workspace (warning only) | Live: SaaS + `local` started; log warns `allow-private-networks is deprecated`; Datacenter refuses the same combination | An operator misconfiguring a deployment | SSRF into the hosting network. Medium | Refuse the `local` profile and `allow-private-networks` in SaaS, as `DatacenterConfigurationValidator` does |
| 5 | No zip-bomb limit on collection import | `collection/CollectionPackage.parseZip` (`readAllBytes` per entry, no size or count cap) | Any signed-in user | Memory exhaustion from a 20 MB upload. Medium | Cap total uncompressed bytes and entry count; stop reading past the cap |
| 6 | ReDoS in `op:matches` | `service/JsonPathEvaluator.java` (`Pattern.compile(pattern).matcher(...).matches()`, no timeout); same in api-automation's `JsonPathEvaluator` | Any user who can edit cases | A pattern like `(a+)+$` pins a server thread. Medium | Run the match with a time limit (interrupt-checking `CharSequence` wrapper) and fail the check on timeout; cap pattern and input length |
| 7 | Management port serves `/actuator/prometheus` without sign-in | Live: 200 on `:18086/actuator/prometheus` | Anyone who can reach the management port | Leaks request paths and usage. Low if the port is private | Document that the port must stay private; optionally bind it to localhost or require a token |
| 8 | Downloads share one folder across workspaces; the target's `Content-Disposition` picks the name; no cleanup | `service/TestRunService.saveDownload` (`studio-output/downloads`) | Signed-in users | One workspace can overwrite another's file; disk grows forever. Low (files aren't served back) | Folder per workspace and case; delete old files on a schedule or a size cap |
| 9 | Malformed JSON returns 500 with a logged stack trace instead of 400 | Live: missing boolean in a body → `HttpMessageNotReadableException` → "Studio is unavailable" | Anyone signed in | Noisy logs, misleading error. Low | Map `HttpMessageNotReadableException` to 400 in `RestExceptionHandler` |
