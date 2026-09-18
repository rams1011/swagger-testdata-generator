package com.tx.api.test.generator.studio.dto;

public record EnvironmentExport(
        String fileName,
        byte[] content,
        String mediaType,
        String environmentId,
        String name
) {
}
