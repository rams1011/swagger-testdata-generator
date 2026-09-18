package com.tx.api.test.generator.studio.dto;

public record CollectionExport(
        String fileName,
        byte[] content,
        String mediaType,
        int caseCount,
        int fileCount
) {
}
