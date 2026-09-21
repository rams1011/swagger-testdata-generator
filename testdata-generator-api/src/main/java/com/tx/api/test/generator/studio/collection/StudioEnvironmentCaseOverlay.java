package com.tx.api.test.generator.studio.collection;

import java.util.Map;

public record StudioEnvironmentCaseOverlay(
        Object body,
        String expectedResponse,
        Map<String, String> pathParams,
        Map<String, String> queryParams,
        Map<String, String> headers,
        Map<String, String> cookies,
        Integer statusCode,
        String jsonPathAssertions
) {
}
