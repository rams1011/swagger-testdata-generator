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
            results.add(result.withRow(row.label()));
            iteration.forEach((name, value) -> {
                if (!rowValues.containsKey(name) || produced.contains(name)) {
                    variables.put(name, value);
                }
            });
        }
        return summary(testCase, results);
    }

    private static RunTestResult failed(StudioTestCase testCase, String message) {
        return new RunTestResult(false, false, null, testCase.getId(), testCase.getTestName(), testCase.getMethod(),
                null, testCase.getStatusCode(), 0, false, 0L, testCase.getMaxResponseTimeMs(), false, null,
                Map.of(), Map.of(), List.of(), message, null, Map.of());
    }

    static RunTestResult summary(StudioTestCase testCase, List<RunTestResult> results) {
        List<AssertionCheck> checks = new ArrayList<>();
        List<String> failedLabels = new ArrayList<>();
        RunTestResult shown = null;
        long elapsed = 0;
        for (RunTestResult result : results) {
            elapsed += result.elapsedMs();
            checks.add(new AssertionCheck(CHECK, result.rowLabel(), "passes",
                    result.passed() ? "passed" : failureOf(result), result.passed()));
            if (!result.passed()) {
                failedLabels.add(result.rowLabel());
                if (shown == null) {
                    shown = result;
                }
            }
        }
        if (shown == null) {
            shown = results.get(results.size() - 1);
        }
        boolean passed = failedLabels.isEmpty();
        String error = passed ? null : failedLabels.size() + " of " + results.size() + " data rows failed: "
                + String.join(", ", failedLabels.subList(0, Math.min(LABELS_IN_ERROR, failedLabels.size())))
                + (failedLabels.size() > LABELS_IN_ERROR ? ", …" : "");
        RunTestResult last = results.get(results.size() - 1);
        return new RunTestResult(passed, false, null, testCase.getId(), testCase.getTestName(), shown.method(),
                shown.url(), shown.expectedStatus(), shown.actualStatus(), shown.statusMatched(), elapsed,
                shown.maxResponseTimeMs(), shown.timedOut(), shown.responseBody(), shown.responseHeaders(),
                shown.requestHeaders(), List.copyOf(checks), error, shown.downloadPath(), last.extracted(),
                shown.requestBody(), null, List.copyOf(results));
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
