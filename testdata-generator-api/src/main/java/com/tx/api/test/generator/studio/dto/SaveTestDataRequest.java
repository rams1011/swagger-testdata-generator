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
        Object requestBody,
        Integer statusCode,
        String responseType,
        String expectedResponse,
        String enabled,
        String testGroup,
        String jsonPathAssertions,
        Long maxResponseTimeMs,
        String contentType
) {
}
