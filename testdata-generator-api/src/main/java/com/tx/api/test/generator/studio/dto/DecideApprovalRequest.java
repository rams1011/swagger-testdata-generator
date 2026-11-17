package com.tx.api.test.generator.studio.dto;

import java.time.Instant;

/** Approving may shorten what was asked ({@code expiresAt}, {@code maxRuns}), never lengthen it. */
public record DecideApprovalRequest(
        Instant expiresAt,
        Integer maxRuns,
        String note
) {
}
