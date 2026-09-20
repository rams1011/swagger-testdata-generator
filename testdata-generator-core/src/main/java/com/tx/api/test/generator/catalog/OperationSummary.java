package com.tx.api.test.generator.catalog;

public record OperationSummary(
        String key,
        String method,
        String path,
        String summary,
        String tag,
        boolean hasRequestBody,
        int successStatus,
        java.util.List<String> environmentNames
) {
}
