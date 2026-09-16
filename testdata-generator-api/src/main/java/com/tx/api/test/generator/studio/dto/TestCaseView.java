package com.tx.api.test.generator.studio.dto;

import java.util.Map;

public record TestCaseView(
        String id,
        String specId,
        String planId,
        String operationKey,
        String testName,
        String method,
        String path,
        String endpoint,
        String baseUrl,
        String contentType,
        Map<String, String> pathParams,
        Map<String, String> queryParams,
        Map<String, String> headers,
        Map<String, String> cookieParams,
        Object requestBody,
        int statusCode,
        String responseType,
        String expectedResponse,
        String enabled,
        String testGroup,
        String jsonPathAssertions,
        String extracts,
        long maxResponseTimeMs
) {
}
