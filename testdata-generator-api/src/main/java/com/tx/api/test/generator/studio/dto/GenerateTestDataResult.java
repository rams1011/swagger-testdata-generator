package com.tx.api.test.generator.studio.dto;

public record GenerateTestDataResult(
        int created,
        int skipped,
        int operations
) {
}
