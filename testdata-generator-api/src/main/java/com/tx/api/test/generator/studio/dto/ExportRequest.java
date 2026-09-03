package com.tx.api.test.generator.studio.dto;

public record ExportRequest(
        String specId,
        String planId,
        String excelFileName
) {
}
