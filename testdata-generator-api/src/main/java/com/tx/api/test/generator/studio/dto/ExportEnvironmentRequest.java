package com.tx.api.test.generator.studio.dto;

public record ExportEnvironmentRequest(
        String specId,
        String name,
        String authType
) {
}
