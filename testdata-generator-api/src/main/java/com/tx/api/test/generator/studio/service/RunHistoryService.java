package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.dto.CaseLastRunView;
import com.tx.api.test.generator.studio.dto.RunCompareField;
import com.tx.api.test.generator.studio.dto.RunCompareView;
import com.tx.api.test.generator.studio.dto.RunResultView;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.dto.RunView;
import com.tx.api.test.generator.studio.model.StudioRun;
import com.tx.api.test.generator.studio.model.StudioRunResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.storage.RunHistoryFilter;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class RunHistoryService {

    static final String TRIGGER_TRY = "try";
    static final String TRIGGER_CASE = "case";
    static final String TRIGGER_PLAN = "plan";

    private static final Set<String> TRIGGERS = Set.of(TRIGGER_TRY, TRIGGER_CASE, TRIGGER_PLAN);
    private static final Set<String> OUTCOMES = Set.of("passed", "failed", "skipped", "error", "blocked");
    private static final int DEFAULT_LIMIT = 100;
    /** How many recent results the Last run column looks through. */
    static final int LATEST_SCAN_LIMIT = 2000;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_BODY_CHARS = 100_000;
    private static final Set<String> SECRET_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "x-auth-token");
    private static final Type ASSERTIONS_TYPE = new TypeToken<List<AssertionCheck>>() { }.getType();
    private static final Gson JSON = new Gson();
    private static final Logger log = LoggerFactory.getLogger(RunHistoryService.class);

    private final StudioStorage studioStorage;
    private final StudioProperties properties;

    public RunHistoryService(StudioStorage studioStorage, StudioProperties properties) {
        this.studioStorage = studioStorage;
        this.properties = properties;
    }

    public void recordSingle(String trigger, String environmentId, StudioTestCase testCase, RunTestResult result) {
        if (testCase == null || result == null) {
            return;
        }
        recordCases(trigger, environmentId, List.of(testCase), List.of(result));
    }

    /** One history run for a Send: the cases that ran first, then the case that was sent. */
    public void recordCases(String trigger, String environmentId, List<StudioTestCase> testCases,
                            List<RunTestResult> results) {
        if (testCases == null || results == null || testCases.isEmpty()) {
            return;
        }
        String specId = null;
        String planId = null;
        List<StudioRunResult> stored = new ArrayList<>();
        int count = Math.min(testCases.size(), results.size());
        for (int i = 0; i < count; i++) {
            StudioTestCase testCase = testCases.get(i);
            RunTestResult result = results.get(i);
            if (testCase == null || result == null || StringUtils.isBlank(testCase.getSpecId())) {
                continue;
            }
            if (specId == null) {
                specId = testCase.getSpecId();
                planId = testCase.getPlanId();
            }
            stored.add(toStoredResult(trigger, testCase, result));
        }
        if (stored.isEmpty() || StringUtils.isBlank(specId)) {
            return;
        }
        persist(buildRun(trigger, specId, planId, environmentId, stored));
    }

    public void recordPlan(String trigger, String specId, String planId, String environmentId,
                           List<RunTestResult> results, List<StudioTestCase> cases) {
        if (StringUtils.isBlank(specId) || results == null || results.isEmpty()) {
            return;
        }
        Map<String, StudioTestCase> byId = new LinkedHashMap<>();
        for (StudioTestCase testCase : cases == null ? List.<StudioTestCase>of() : cases) {
            if (testCase.getId() != null) {
                byId.put(testCase.getId(), testCase);
            }
        }
        List<StudioRunResult> stored = new ArrayList<>();
        for (RunTestResult result : results) {
            StudioTestCase testCase = result.caseId() == null ? null : byId.get(result.caseId());
            if (testCase == null) {
                continue;
            }
            stored.add(toStoredResult(trigger, testCase, result));
        }
        persist(buildRun(trigger, specId, planId, environmentId, stored));
    }

    public List<RunView> list(String specId, String planId, String environmentId, String caseId, String outcome,
                              String trigger, Integer limit) {
        if (StringUtils.isBlank(specId)) {
            throw new IllegalArgumentException("specId is required");
        }
        return studioStorage.listRuns(new RunHistoryFilter(
                        specId.trim(),
                        blankToNull(planId),
                        blankToNull(environmentId),
                        blankToNull(caseId),
                        normalizeChoice(outcome, OUTCOMES, "outcome"),
                        normalizeChoice(trigger, TRIGGERS, "trigger"),
                        resolveLimit(limit)))
                .stream()
                .map(run -> toView(run, false))
                .toList();
    }

    /** The newest result of each case that ran in this spec (and plan / environment when given). */
    public List<CaseLastRunView> latestByCase(String specId, String planId, String environmentId) {
        if (StringUtils.isBlank(specId)) {
            throw new IllegalArgumentException("specId is required");
        }
        return studioStorage.latestCaseRuns(specId.trim(), blankToNull(planId), blankToNull(environmentId),
                LATEST_SCAN_LIMIT);
    }

    public RunView get(String runId) {
        StudioRun run = studioStorage.findRun(runId)
                .orElseThrow(() -> new NoSuchElementException("Run not found: " + runId));
        return toView(run, true);
    }

    public RunResultView getResult(String runId, String resultId) {
        StudioRunResult result = studioStorage.findRunResult(runId, resultId)
                .orElseThrow(() -> new NoSuchElementException("Run result not found: " + resultId));
        return toResultView(result);
    }

    public void delete(String runId) {
        studioStorage.deleteRun(runId);
    }

    public RunCompareView compare(String leftRunId, String leftResultId, String rightRunId, String rightResultId) {
        RunResultView left = getResult(leftRunId, leftResultId);
        RunResultView right = getResult(rightRunId, rightResultId);
        return new RunCompareView(left, right, diff(left, right));
    }

    private void persist(StudioRun run) {
        if (run.getResults().isEmpty()) {
            return;
        }
        try {
            studioStorage.saveRun(run);
        } catch (RuntimeException exception) {
            log.warn("Failed to persist run history for spec {}", run.getSpecId(), exception);
            return;
        }
        prune(run.getSpecId());
    }

    private void prune(String specId) {
        int keep = properties.getRunHistory().resolvedMaxPerSpec();
        if (keep <= 0) {
            return;
        }
        try {
            int removed = studioStorage.pruneOldestRuns(specId, keep);
            if (removed > 0) {
                log.info("Pruned {} oldest run(s) for spec {} (keeping {})", removed, specId, keep);
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to prune run history for spec {}", specId, exception);
        }
    }

    private StudioRun buildRun(String trigger, String specId, String planId, String environmentId,
                               List<StudioRunResult> results) {
        Instant now = Instant.now();
        String runId = UUID.randomUUID().toString();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (StudioRunResult result : results) {
            result.setRunId(runId);
            result.setCreatedAt(now);
            switch (StringUtils.defaultString(result.getOutcome())) {
                case "passed" -> passed++;
                // td_run has no blocked column: a run's blocked cases count as skipped; each result keeps "blocked".
                case "skipped", "blocked" -> skipped++;
                default -> failed++;
            }
        }
        StudioRun run = new StudioRun();
        run.setId(runId);
        run.setSpecId(specId);
        run.setPlanId(planId);
        run.setEnvironmentId(blankToNull(environmentId));
        run.setTrigger(trigger);
        run.setPassed(passed);
        run.setFailed(failed);
        run.setSkipped(skipped);
        run.setStartedAt(now);
        run.setResults(results);
        return run;
    }

    private StudioRunResult toStoredResult(String trigger, StudioTestCase testCase, RunTestResult result) {
        StudioRunResult stored = new StudioRunResult();
        stored.setId(UUID.randomUUID().toString());
        stored.setSpecId(testCase.getSpecId());
        stored.setPlanId(testCase.getPlanId());
        stored.setCaseId(StringUtils.isBlank(testCase.getId()) ? null : testCase.getId());
        stored.setOperationKey(testCase.getOperationKey());
        stored.setTestName(result.testName());
        stored.setTrigger(trigger);
        stored.setOutcome(outcome(result));
        stored.setMethod(result.method());
        stored.setUrl(result.url());
        stored.setExpectedStatus(result.expectedStatus());
        stored.setActualStatus(result.actualStatus());
        stored.setStatusMatched(result.statusMatched());
        stored.setElapsedMs(result.elapsedMs());
        stored.setMaxResponseTimeMs(result.maxResponseTimeMs());
        stored.setTimedOut(result.timedOut());
        stored.setError(result.skipped() && StringUtils.isBlank(result.error())
                ? result.skipReason()
                : result.error());
        stored.setRequestHeaders(redactHeaders(result.requestHeaders()));
        stored.setRequestBody(truncate(requestBodySnapshot(testCase)));
        stored.setRequestContentType(testCase.getContentType());
        stored.setRequestParams(requestParams(testCase));
        stored.setResponseHeaders(redactHeaders(result.responseHeaders()));
        stored.setResponseBody(truncate(result.responseBody()));
        stored.setAssertionsJson(JSON.toJson(result.assertions() == null ? List.of() : result.assertions()));
        stored.setDownloadPath(result.downloadPath());
        return stored;
    }

    /** The stored outcome of one result: blocked, skipped, passed, error (no response) or failed. */
    static String outcome(RunTestResult result) {
        if (result.blocked()) {
            return "blocked";
        }
        if (result.skipped()) {
            return "skipped";
        }
        if (result.passed()) {
            return "passed";
        }
        // A data-driven case's error only sums up its failed rows: that's a failure, not a request error, unless no
        // row got a response at all (an unresolved variable, a connection error), which is an error like a single case.
        if (StringUtils.isNotBlank(result.error()) && (result.iterations() == null || noRowCompleted(result))) {
            return "error";
        }
        return "failed";
    }

    private static boolean noRowCompleted(RunTestResult result) {
        return !result.iterations().isEmpty() && result.iterations().stream().allMatch(row -> row != null
                && !row.passed() && !row.skipped() && !row.blocked()
                && row.actualStatus() == 0 && StringUtils.isNotBlank(row.error()));
    }

    private static Map<String, String> redactHeaders(Map<String, String> headers) {
        Map<String, String> redacted = new LinkedHashMap<>();
        if (headers == null) {
            return redacted;
        }
        headers.forEach((name, value) -> {
            String key = StringUtils.defaultString(name);
            String lower = key.toLowerCase(Locale.ROOT);
            boolean secret = SECRET_HEADERS.contains(lower)
                    || lower.contains("token")
                    || lower.contains("secret")
                    || lower.contains("password")
                    || lower.contains("api-key");
            redacted.put(key, secret ? "[redacted]" : value);
        });
        return redacted;
    }

    private static Map<String, String> requestParams(StudioTestCase testCase) {
        Map<String, String> params = new LinkedHashMap<>();
        putPrefixed(params, "path", testCase.getPathParams());
        putPrefixed(params, "query", testCase.getQueryParams());
        putPrefixed(params, "cookie", testCase.getCookieParams());
        return params;
    }

    private static void putPrefixed(Map<String, String> target, String prefix, Map<String, String> source) {
        if (source == null) {
            return;
        }
        source.forEach((name, value) -> target.put(prefix + "." + name, value));
    }

    private static String requestBodySnapshot(StudioTestCase testCase) {
        Object body = testCase.getRequestBody();
        if (body == null) {
            return null;
        }
        if (body instanceof String text) {
            return text;
        }
        return JSON.toJson(body);
    }

    private static String truncate(String body) {
        if (body == null || body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        return body.substring(0, MAX_BODY_CHARS) + "…";
    }

    private static String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private static String normalizeChoice(String value, Set<String> allowed, String label) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(label + " must be one of " + allowed);
        }
        return normalized;
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private RunView toView(StudioRun run, boolean includeResults) {
        List<RunResultView> results = includeResults
                ? run.getResults().stream().map(this::toResultView).toList()
                : List.of();
        return new RunView(
                run.getId(),
                run.getSpecId(),
                run.getPlanId(),
                run.getEnvironmentId(),
                run.getTrigger(),
                run.getPassed(),
                run.getFailed(),
                run.getSkipped(),
                run.getStartedAt(),
                results);
    }

    private RunResultView toResultView(StudioRunResult result) {
        List<AssertionCheck> assertions = List.of();
        if (StringUtils.isNotBlank(result.getAssertionsJson())) {
            List<AssertionCheck> parsed = JSON.fromJson(result.getAssertionsJson(), ASSERTIONS_TYPE);
            if (parsed != null) {
                assertions = parsed;
            }
        }
        return new RunResultView(
                result.getId(),
                result.getRunId(),
                result.getSpecId(),
                result.getPlanId(),
                result.getCaseId(),
                result.getOperationKey(),
                result.getTestName(),
                result.getTrigger(),
                result.getOutcome(),
                result.getMethod(),
                result.getUrl(),
                result.getExpectedStatus(),
                result.getActualStatus(),
                result.getStatusMatched(),
                result.getElapsedMs(),
                result.getMaxResponseTimeMs(),
                result.getTimedOut(),
                result.getError(),
                result.getRequestHeaders(),
                result.getRequestBody(),
                result.getRequestContentType(),
                result.getRequestParams(),
                result.getResponseHeaders(),
                result.getResponseBody(),
                assertions,
                result.getDownloadPath(),
                result.getCreatedAt());
    }

    private List<RunCompareField> diff(RunResultView left, RunResultView right) {
        List<RunCompareField> fields = new ArrayList<>();
        addDiff(fields, "outcome", left.outcome(), right.outcome());
        addDiff(fields, "testName", left.testName(), right.testName());
        addDiff(fields, "operationKey", left.operationKey(), right.operationKey());
        addDiff(fields, "caseId", left.caseId(), right.caseId());
        addDiff(fields, "method", left.method(), right.method());
        addDiff(fields, "url", left.url(), right.url());
        addDiff(fields, "expectedStatus", left.expectedStatus(), right.expectedStatus());
        addDiff(fields, "actualStatus", left.actualStatus(), right.actualStatus());
        addDiff(fields, "statusMatched", left.statusMatched(), right.statusMatched());
        addDiff(fields, "elapsedMs", left.elapsedMs(), right.elapsedMs());
        addDiff(fields, "timedOut", left.timedOut(), right.timedOut());
        addDiff(fields, "error", left.error(), right.error());
        addDiff(fields, "requestContentType", left.requestContentType(), right.requestContentType());
        addDiff(fields, "requestParams", JSON.toJson(left.requestParams()), JSON.toJson(right.requestParams()));
        addDiff(fields, "requestBody", normalizeBody(left.requestBody()), normalizeBody(right.requestBody()));
        addDiff(fields, "responseBody", normalizeBody(left.responseBody()), normalizeBody(right.responseBody()));
        addDiff(fields, "assertions", JSON.toJson(left.assertions()), JSON.toJson(right.assertions()));
        addDiff(fields, "downloadPath", left.downloadPath(), right.downloadPath());
        return fields;
    }

    private static void addDiff(List<RunCompareField> fields, String name, Object left, Object right) {
        String leftText = stringify(left);
        String rightText = stringify(right);
        fields.add(new RunCompareField(name, leftText, rightText, leftText.equals(rightText)));
    }

    private static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String normalizeBody(String body) {
        if (StringUtils.isBlank(body)) {
            return "";
        }
        try {
            return JSON.toJson(JsonParser.parseString(body));
        } catch (RuntimeException ignored) {
            return body;
        }
    }
}
