package com.tx.api.test.generator.studio.dto;

import java.util.List;

/**
 * What a Run plan or Run selected would do in a protected environment, before it runs (PR 5's plan check).
 * {@code willRun} counts cases that would be sent; {@code needsConfirmation} are approved writes, which need
 * {@code confirmationText} typed; {@code blocked} are stopped; {@code skipped} need a value from a blocked case.
 */
public record PlanPreflightView(
        boolean enabled,
        String mode,
        boolean protectedEnvironment,
        String confirmationText,
        int willRun,
        List<Entry> needsConfirmation,
        List<Entry> blocked,
        List<Entry> skipped
) {
    public record Entry(String caseId, String name, String code, String message) {
    }
}
