package com.tx.api.test.generator.generate;

import java.util.Map;

/**
 * A draft test case produced from an operation's OpenAPI/JSON Schema.
 */
public record GeneratedTestCase(
        String operationKey,
        String testName,
        boolean positive,
        String testGroup,
        Map<String, String> pathParams,
        Map<String, String> queryParams,
        Map<String, String> headers,
        Map<String, String> cookieParams,
        Object requestBody,
        String contentType,
        int statusCode,
        String responseType,
        String expectedResponse,
        String jsonPathAssertions,
        String enabled,
        long maxResponseTimeMs
) {
}
