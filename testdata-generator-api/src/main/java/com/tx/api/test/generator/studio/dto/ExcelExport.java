package com.tx.api.test.generator.studio.dto;

public record ExcelExport(String fileName, byte[] content, int rowCount, String mediaType, int fileCount) {
}
