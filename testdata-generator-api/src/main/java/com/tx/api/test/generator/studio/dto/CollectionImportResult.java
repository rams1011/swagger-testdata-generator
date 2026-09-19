package com.tx.api.test.generator.studio.dto;

public record CollectionImportResult(
        String specId,
        String specName,
        String planId,
        String planName,
        boolean planCreated,
        int createdCases,
        int updatedCases,
        int importedFiles,
        int skippedFiles
) {
}
