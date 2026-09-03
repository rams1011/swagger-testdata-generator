package com.tx.api.test.generator.studio.dto;

import java.time.Instant;

public record RequestFileView(
        String fileId,
        String testCaseId,
        String fileName,
        String contentType,
        long sizeBytes,
        Instant createdAt
) {
}
