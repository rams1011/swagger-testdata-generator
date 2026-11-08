package com.tx.api.test.generator.studio.dto;

import java.util.List;
import java.util.Map;

public record RunTestResult(
        boolean passed,
        boolean skipped,
        String skipReason,
        String caseId,
        String testName,
        String method,
        String url,
        int expectedStatus,
        int actualStatus,
        boolean statusMatched,
        long elapsedMs,
        long maxResponseTimeMs,
        boolean timedOut,
        String responseBody,
        Map<String, String> responseHeaders,
        Map<String, String> requestHeaders,
        List<AssertionCheck> assertions,
        String error,
        String downloadPath,
        Map<String, String> extracted,
        /** Request body as sent (after variables), for Copy as cURL; a placeholder for multipart; null for none. */
        String requestBody,
        /** The data row this result is for ($name or "Row 3"); null for a case without data rows. */
        String rowLabel,
        /** For a case with data rows: one result per row, in order; this result sums them up. Null otherwise. */
        List<RunTestResult> iterations
) {
    public RunTestResult(boolean passed, boolean skipped, String skipReason, String caseId, String testName,
                         String method, String url, int expectedStatus, int actualStatus, boolean statusMatched,
                         long elapsedMs, long maxResponseTimeMs, boolean timedOut, String responseBody,
                         Map<String, String> responseHeaders, Map<String, String> requestHeaders,
                         List<AssertionCheck> assertions, String error, String downloadPath,
                         Map<String, String> extracted, String requestBody) {
        this(passed, skipped, skipReason, caseId, testName, method, url, expectedStatus, actualStatus, statusMatched,
                elapsedMs, maxResponseTimeMs, timedOut, responseBody, responseHeaders, requestHeaders, assertions,
                error, downloadPath, extracted, requestBody, null, null);
    }

    public RunTestResult(boolean passed, boolean skipped, String skipReason, String caseId, String testName,
                         String method, String url, int expectedStatus, int actualStatus, boolean statusMatched,
                         long elapsedMs, long maxResponseTimeMs, boolean timedOut, String responseBody,
                         Map<String, String> responseHeaders, Map<String, String> requestHeaders,
                         List<AssertionCheck> assertions, String error, String downloadPath,
                         Map<String, String> extracted) {
        this(passed, skipped, skipReason, caseId, testName, method, url, expectedStatus, actualStatus, statusMatched,
                elapsedMs, maxResponseTimeMs, timedOut, responseBody, responseHeaders, requestHeaders, assertions,
                error, downloadPath, extracted, null, null, null);
    }

    public RunTestResult withRequestBody(String body) {
        return new RunTestResult(passed, skipped, skipReason, caseId, testName, method, url, expectedStatus,
                actualStatus, statusMatched, elapsedMs, maxResponseTimeMs, timedOut, responseBody, responseHeaders,
                requestHeaders, assertions, error, downloadPath, extracted, body, rowLabel, iterations);
    }

    public RunTestResult withRow(String label) {
        return new RunTestResult(passed, skipped, skipReason, caseId, testName, method, url, expectedStatus,
                actualStatus, statusMatched, elapsedMs, maxResponseTimeMs, timedOut, responseBody, responseHeaders,
                requestHeaders, assertions, error, downloadPath, extracted, requestBody, label, iterations);
    }

    public RunTestResult withAssertions(List<AssertionCheck> checks) {
        return new RunTestResult(passed, skipped, skipReason, caseId, testName, method, url, expectedStatus,
                actualStatus, statusMatched, elapsedMs, maxResponseTimeMs, timedOut, responseBody, responseHeaders,
                requestHeaders, checks, error, downloadPath, extracted, requestBody, rowLabel, iterations);
    }

    @Override
    public String toString() {
        return "RunTestResult[passed=" + passed
                + ", skipped=" + skipped
                + ", status=" + actualStatus
                + ", elapsedMs=" + elapsedMs
                + ", url=" + url + "]";
    }
}
