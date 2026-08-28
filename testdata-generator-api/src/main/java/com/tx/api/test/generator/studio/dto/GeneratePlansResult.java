package com.tx.api.test.generator.studio.dto;

public record GeneratePlansResult(
        TestPlanView smokePlan,
        GenerateTestDataResult smoke,
        TestPlanView negativePlan,
        GenerateTestDataResult negative
) {
}
