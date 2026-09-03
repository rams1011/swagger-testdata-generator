package com.tx.api.test.generator.studio.dto;

import com.tx.api.test.generator.catalog.SpecServer;

import java.util.List;

public record SpecSummary(
        String specId,
        String name,
        String label,
        String title,
        String version,
        String description,
        String baseUrl,
        int operationCount,
        List<String> tags,
        String sourceLocation,
        List<SpecServer> servers
) {
}
