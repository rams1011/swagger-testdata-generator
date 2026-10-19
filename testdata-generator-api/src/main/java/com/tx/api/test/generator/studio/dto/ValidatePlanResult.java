package com.tx.api.test.generator.studio.dto;

import java.util.List;

public record ValidatePlanResult(
        String specId,
        String planId,
        int runnableCount,
        int skippedDisabled,
        int skippedEnvironment,
        boolean ready,
        List<ValidatePlanCaseEntry> executionOrder,
        List<String> warnings
) {
}
