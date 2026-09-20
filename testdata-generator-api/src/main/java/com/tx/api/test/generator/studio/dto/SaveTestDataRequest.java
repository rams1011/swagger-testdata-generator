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
        String enabled,
        String testGroup,
        String jsonPathAssertions,
        String extracts,
        Long maxResponseTimeMs,
        String contentType,
        String environmentId,
        java.util.List<String> environments
) {
}
