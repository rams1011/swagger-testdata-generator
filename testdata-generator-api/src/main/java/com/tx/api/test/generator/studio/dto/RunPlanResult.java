package com.tx.api.test.generator.studio.dto;

import java.util.List;

public record RunPlanResult(
        String specId,
        String planId,
        int passed,
        int failed,
        int skipped,
        boolean allPassed,
        List<RunTestResult> results,
        /** Cases production safety stopped (not sent); they aren't counted in {@code skipped}. */
        int blocked
) {
    public RunPlanResult(String specId, String planId, int passed, int failed, int skipped, boolean allPassed,
                         List<RunTestResult> results) {
        this(specId, planId, passed, failed, skipped, allPassed, results, 0);
    }

    @Override
    public String toString() {
        return "RunPlanResult[passed=" + passed
                + ", failed=" + failed
                + ", skipped=" + skipped
                + ", blocked=" + blocked
                + ", allPassed=" + allPassed + "]";
    }
}
