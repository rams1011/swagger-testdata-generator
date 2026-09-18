package com.tx.api.test.generator.studio.collection;

import java.util.List;
import java.util.Map;

public record StudioCollectionCase(
        String id,
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
        Map<String, String> cookies,
        Object body,
        Map<String, Object> responseJsonSchema,
        int statusCode,
        String responseType,
        String expectedResponse,
        String enabled,
        String testGroup,
        String jsonPathAssertions,
        String extracts,
        Long maxResponseTimeMs,
        List<StudioCollectionFile> files
) {
}
