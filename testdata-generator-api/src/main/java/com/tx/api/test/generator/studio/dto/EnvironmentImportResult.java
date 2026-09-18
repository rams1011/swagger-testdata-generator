package com.tx.api.test.generator.studio.dto;

public record EnvironmentImportResult(
        String specId,
        String environmentId,
        String name,
        String baseUrl,
        boolean applied,
        boolean created
) {
}
