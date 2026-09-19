package com.tx.api.test.generator.studio.dto;

import java.util.List;

public record ManualOperationRequest(
        String method,
        String path,
        String summary,
        String tag,
        String description,
        Integer statusCode,
        Boolean hasRequestBody,
        String requestExample,
        List<String> queryNames,
        List<String> headerNames
) {
}
