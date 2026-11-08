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
        String responseMatch,
        String ignoreFields,
        com.tx.api.test.generator.studio.dto.RunAuth auth,
        String enabled,
        String lifecycle,
        String testGroup,
        String jsonPathAssertions,
        String extracts,
        Long maxResponseTimeMs,
        List<StudioCollectionFile> files,
        List<String> environments,
        /** Data rows: an array of objects, one run per row (see DataRows). Absent for a case that runs once. */
        com.google.gson.JsonElement data
) {

    public StudioCollectionCase(String id, String operationKey, String testName, String method, String path,
                                String endpoint, String baseUrl, String contentType, Map<String, String> pathParams,
                                Map<String, String> queryParams, Map<String, String> headers,
                                Map<String, String> cookies, Object body, Map<String, Object> responseJsonSchema,
                                int statusCode, String responseType, String expectedResponse, String responseMatch,
                                String ignoreFields, com.tx.api.test.generator.studio.dto.RunAuth auth,
                                String enabled, String lifecycle, String testGroup, String jsonPathAssertions,
                                String extracts, Long maxResponseTimeMs, List<StudioCollectionFile> files,
                                List<String> environments) {
        this(id, operationKey, testName, method, path, endpoint, baseUrl, contentType, pathParams, queryParams,
                headers, cookies, body, responseJsonSchema, statusCode, responseType, expectedResponse, responseMatch,
                ignoreFields, auth, enabled, lifecycle, testGroup, jsonPathAssertions, extracts, maxResponseTimeMs,
                files, environments, null);
    }
}
