package com.tx.api.test.generator.studio.dto;

/** Mode and limits. Null keeps a value; {@code expectedRevision} is required (409 when someone saved first). */
public record UpdateSafetySettingsRequest(
        String mode,
        Integer maxRequestsPerRun,
        Integer maxRequestsPerMinute,
        Integer maxWriteDataRows,
        Boolean requireSecondAdmin,
        String selfApproval,
        Long expectedRevision
) {
}
