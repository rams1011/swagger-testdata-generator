package com.tx.api.test.generator.catalog;

import java.util.Map;

public record SpecServer(
        String url,
        String description,
        String resolvedUrl,
        Map<String, String> variables
) {
}
