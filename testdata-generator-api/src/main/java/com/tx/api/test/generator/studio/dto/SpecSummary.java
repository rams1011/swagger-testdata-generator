package com.tx.api.test.generator.studio.dto;

import java.util.List;

public record SpecSummary(
        String specId,
        String title,
        String version,
        String description,
        String baseUrl,
        int operationCount,
        List<String> tags
) {
}
