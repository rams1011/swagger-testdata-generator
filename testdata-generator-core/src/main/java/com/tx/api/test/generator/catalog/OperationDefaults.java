package com.tx.api.test.generator.catalog;

public record OperationDefaults(
        String testName,
        String testGroup,
        String contentType,
        String responseType,
        String enabled,
        long maxResponseTimeMs,
        String baseUrl
) {
}
