package com.tx.api.test.generator.studio.dto;

/** {@code days}: 1 to 90 (the UI offers 1, 7 or 30). {@code maxRuns}: 1 to 100, or null for unlimited. */
public record RequestApprovalRequest(
        String environmentId,
        String reason,
        Integer days,
        Integer maxRuns
) {
}
