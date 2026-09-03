package com.tx.api.test.generator.studio.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RunResultView(
        String resultId,
        String runId,
        String specId,
        String planId,
        String caseId,
        String operationKey,
        String testName,
        String trigger,
        String outcome,
        String method,
        String url,
        Integer expectedStatus,
        Integer actualStatus,
        Boolean statusMatched,
        Long elapsedMs,
        Long maxResponseTimeMs,
        Boolean timedOut,
        String error,
        Map<String, String> requestHeaders,
        String requestBody,
        String requestContentType,
        Map<String, String> requestParams,
        Map<String, String> responseHeaders,
        String responseBody,
        List<AssertionCheck> assertions,
        String downloadPath,
        Instant createdAt
) {
}
