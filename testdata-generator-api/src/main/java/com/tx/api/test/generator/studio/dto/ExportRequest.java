package com.tx.api.test.generator.studio.dto;

public record ExportRequest(
        String specId,
        String planId,
        String outputDir,
        String excelFileName,
        String payloadDir,
        String schemaDir,
        String payloadPathPrefix,
        String schemaPathPrefix
) {
}
