package com.tx.api.test.generator.catalog;

import java.util.Map;

public record ResponseSchema(
        int statusCode,
        String contentType,
        String responseType,
        Map<String, Object> jsonSchema,
        Object sample,
        String jsonPathAssertions
) {
}
