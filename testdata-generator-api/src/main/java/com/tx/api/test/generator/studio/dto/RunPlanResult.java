package com.tx.api.test.generator.studio.dto;

import java.util.List;

public record RunPlanResult(
        String specId,
        String planId,
        int passed,
        int failed,
        int skipped,
        boolean allPassed,
        List<RunTestResult> results
) {
    @Override
    public String toString() {
        return "RunPlanResult[passed=" + passed
                + ", failed=" + failed
                + ", skipped=" + skipped
                + ", allPassed=" + allPassed + "]";
    }
}
