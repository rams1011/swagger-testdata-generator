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
        String downloadPath
) {
    @Override
    public String toString() {
        return "RunTestResult[passed=" + passed
                + ", skipped=" + skipped
                + ", status=" + actualStatus
                + ", elapsedMs=" + elapsedMs
                + ", url=" + url + "]";
    }
}
