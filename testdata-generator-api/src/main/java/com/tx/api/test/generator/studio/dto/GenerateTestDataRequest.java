package com.tx.api.test.generator.studio.dto;

public record GenerateTestDataRequest(
        String specId,
        String planId,
        String operationKey,
        Boolean includePositive,
        Boolean includeNegative
) {
}
