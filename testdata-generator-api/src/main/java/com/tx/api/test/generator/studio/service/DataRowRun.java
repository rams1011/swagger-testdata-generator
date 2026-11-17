package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sends a data-driven case once per row. Each row runs with the run's variables plus its own values; what a row
 * extracts carries on to later rows and cases, its own values don't. The result sums the rows up, with one
 * "data row" check per row and each row's full result in {@code iterations}.
 */
final class DataRowRun {

    static final String CHECK = "data row";
    private static final int LABELS_IN_ERROR = 5;

    interface Sender {
        RunTestResult send(StudioTestCase testCase, Map<String, String> variables);
    }

    private DataRowRun() {
    }

    static RunTestResult run(StudioTestCase testCase, List<DataRows.Row> rows, Map<String, String> variables,
                             Sender sender) {
        Set<String> produced = CaseVariables.producedNames(testCase.getExtracts());
        Set<String> columns = new java.util.HashSet<>();
        rows.forEach(row -> columns.addAll(DataRows.variables(row).keySet()));
        List<RunTestResult> results = new ArrayList<>();
        for (DataRows.Row row : rows) {
            Map<String, String> rowValues = DataRows.variables(row);
            Map<String, String> iteration = new LinkedHashMap<>(variables);
            iteration.putAll(rowValues);
            RunTestResult result;
            try {
                result = sender.send(DataRows.apply(testCase, row), iteration);
            } catch (IllegalArgumentException invalid) {
                result = failed(testCase, invalid.getMessage());
            }
            results.add(explainMissingCell(result, row.label(), rowValues, columns).withRow(row.label()));
            iteration.forEach((name, value) -> {
                if (!rowValues.containsKey(name) || produced.contains(name)) {
                    variables.put(name, value);
                }
            });
        }
        return summary(testCase, results);
    }

    /**
     * A column other rows have but this one doesn't (an empty CSV cell leaves it out) fails as "Unresolved variable",
     * whose advice points at environment variables. Say which row and column instead.
     */
    static RunTestResult explainMissingCell(RunTestResult result, String rowLabel, Map<String, String> rowValues,
                                            Set<String> columns) {
        String error = result.error();
        if (error == null || !error.startsWith("Unresolved variable {{")) {
            return result;
        }
        int end = error.indexOf("}}");
        String name = end < 0 ? "" : error.substring("Unresolved variable {{".length(), end);
        String column = name.startsWith(DataRows.DATA_PREFIX) ? name.substring(DataRows.DATA_PREFIX.length()) : name;
        if (!columns.contains(column) || rowValues.containsKey(column)) {
            return result;
        }
        return result.withError("Data row \"" + rowLabel + "\" has no value for {{" + name + "}}: fill in its "
                + column + " cell. An empty cell leaves the column out of that row.");
    }

    private static RunTestResult failed(StudioTestCase testCase, String message) {
        return new RunTestResult(false, false, null, testCase.getId(), testCase.getTestName(), testCase.getMethod(),
                null, testCase.getStatusCode(), 0, false, 0L, testCase.getMaxResponseTimeMs(), false, null,
                Map.of(), Map.of(), List.of(), message, null, Map.of());
    }

    static RunTestResult summary(StudioTestCase testCase, List<RunTestResult> results) {
        List<AssertionCheck> checks = new ArrayList<>();
        List<String> failedLabels = new ArrayList<>();
        List<String> blockedLabels = new ArrayList<>();
        RunTestResult shown = null;
        com.tx.api.test.generator.studio.dto.SafetyPolicyNote policy = null;
        long elapsed = 0;
        for (RunTestResult result : results) {
            elapsed += result.elapsedMs();
            if (policy == null) {
                policy = result.policy();
            }
            checks.add(new AssertionCheck(CHECK, result.rowLabel(), "passes",
                    result.passed() ? "passed" : result.blocked() ? "blocked" : failureOf(result), result.passed()));
            if (!result.passed()) {
                (result.blocked() ? blockedLabels : failedLabels).add(result.rowLabel());
                if (shown == null) {
                    shown = result;
                }
            }
        }
        if (shown == null) {
            shown = results.get(results.size() - 1);
        }
        RunTestResult last = results.get(results.size() - 1);
        if (blockedLabels.size() == results.size()) {
            // Production safety stopped every row: the case was blocked, not failed.
            return new RunTestResult(false, true, shown.skipReason(), testCase.getId(), testCase.getTestName(),
                    shown.method(), shown.url(), shown.expectedStatus(), 0, false, elapsed,
                    shown.maxResponseTimeMs(), false, null, Map.of(), shown.requestHeaders(), List.copyOf(checks),
                    null, null, last.extracted(), shown.requestBody(), null, List.copyOf(results), true, policy);
        }
        boolean passed = failedLabels.isEmpty() && blockedLabels.isEmpty();
        List<String> problems = new ArrayList<>();
        if (!failedLabels.isEmpty()) {
            problems.add(failedLabels.size() + " of " + results.size() + " data rows failed: " + labels(failedLabels));
        }
        if (!blockedLabels.isEmpty()) {
            problems.add(blockedLabels.size() + " of " + results.size() + " data rows were blocked by production "
                    + "safety: " + labels(blockedLabels));
        }
        String error = passed ? null : String.join("; ", problems);
        return new RunTestResult(passed, false, null, testCase.getId(), testCase.getTestName(), shown.method(),
                shown.url(), shown.expectedStatus(), shown.actualStatus(), shown.statusMatched(), elapsed,
                shown.maxResponseTimeMs(), shown.timedOut(), shown.responseBody(), shown.responseHeaders(),
                shown.requestHeaders(), List.copyOf(checks), error, shown.downloadPath(), last.extracted(),
                shown.requestBody(), null, List.copyOf(results), false, policy);
    }

    private static String labels(List<String> labels) {
        return String.join(", ", labels.subList(0, Math.min(LABELS_IN_ERROR, labels.size())))
                + (labels.size() > LABELS_IN_ERROR ? ", …" : "");
    }

    private static String failureOf(RunTestResult result) {
        if (StringUtils.isNotBlank(result.error())) {
            return StringUtils.abbreviate(result.error(), 300);
        }
        if (result.assertions() != null) {
            for (AssertionCheck check : result.assertions()) {
                if (check != null && !check.passed()) {
                    return StringUtils.abbreviate("failed " + check.name()
                            + (StringUtils.isBlank(check.path()) ? "" : " " + check.path()), 300);
                }
            }
        }
        return result.statusMatched() ? "failed" : "status " + result.actualStatus();
    }
}
