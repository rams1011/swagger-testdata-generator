package com.tx.api.test.generator.studio.dto;

/** Home page card details for one collection: case and environment counts and the newest run (or null). */
public record SpecActivityView(
        String specId,
        int caseCount,
        int environmentCount,
        RunView lastRun
) {
}
