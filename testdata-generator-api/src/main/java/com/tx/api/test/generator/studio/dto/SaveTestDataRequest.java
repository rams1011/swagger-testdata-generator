package com.tx.api.test.generator.studio.dto;

import java.util.Map;

public record SaveTestDataRequest(
        String specId,
        String planId,
        String operationKey,
        String testName,
        String baseUrl,
        Map<String, String> pathParams,
        Map<String, String> queryParams,
        Map<String, String> headers,
        Map<String, String> cookieParams,
        Object requestBody,
        Integer statusCode,
        String responseType,
        String expectedResponse,
        String responseMatch,
        String ignoreFields,
        RunAuth auth,
        String enabled,
        String lifecycle,
        String testGroup,
        String jsonPathAssertions,
        String extracts,
        Long maxResponseTimeMs,
        String contentType,
        String environmentId,
        java.util.List<String> environments,
        Long expectedRevision,
        Boolean forceOverwrite,
        Long expectedDraftRevision,
        Boolean keepDraft,
        /** Data rows: a list of objects, one run per row. Null keeps the stored rows; an empty list removes them. */
        Object dataRows
) {

    public SaveTestDataRequest(String specId, String planId, String operationKey, String testName, String baseUrl,
                               Map<String, String> pathParams, Map<String, String> queryParams,
                               Map<String, String> headers, Map<String, String> cookieParams, Object requestBody,
                               Integer statusCode, String responseType, String expectedResponse,
                               String responseMatch, String ignoreFields, RunAuth auth, String enabled,
                               String lifecycle, String testGroup, String jsonPathAssertions, String extracts,
                               Long maxResponseTimeMs, String contentType, String environmentId,
                               java.util.List<String> environments, Long expectedRevision, Boolean forceOverwrite,
                               Long expectedDraftRevision, Boolean keepDraft) {
        this(specId, planId, operationKey, testName, baseUrl, pathParams, queryParams, headers, cookieParams,
                requestBody, statusCode, responseType, expectedResponse, responseMatch, ignoreFields, auth, enabled,
                lifecycle, testGroup, jsonPathAssertions, extracts, maxResponseTimeMs, contentType, environmentId,
                environments, expectedRevision, forceOverwrite, expectedDraftRevision, keepDraft, null);
    }
}
