package com.tx.api.test.generator.catalog;

import java.util.Map;

public record ParameterField(
        String name,
        String in,
        boolean required,
        String description,
        Map<String, Object> schema,
        String sample
) {
}
