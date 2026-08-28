package com.tx.api.test.generator.studio.dto;

public record GeneratePlansRequest(
        String specId,
        String smokePlanName,
        Boolean includeNegative,
        String negativePlanName
) {
}
