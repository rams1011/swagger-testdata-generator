package com.tx.api.test.generator.catalog;

import java.util.Map;

public record RequestBodySchema(
        boolean required,
        String contentType,
        String mappedContentType,
        Map<String, Object> jsonSchema,
        Object sample
) {
}
