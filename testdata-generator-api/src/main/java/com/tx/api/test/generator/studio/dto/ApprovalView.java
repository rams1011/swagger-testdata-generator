package com.tx.api.test.generator.studio.dto;

import java.time.Instant;

/**
 * A production write approval, for the case editor and the admins' inbox. {@code coversCurrentVersion} is false
 * once the case was published again, restored or imported over: the approval no longer applies.
 */
public record ApprovalView(
        String id,
        String caseId,
        String caseName,
        String specId,
        long caseRevision,
        boolean coversCurrentVersion,
        String environmentId,
        String environmentName,
        String status,
        String reason,
        String requestedBy,
        String requestedByLabel,
        Instant requestedAt,
        String decidedByLabel,
        Instant decidedAt,
        String decisionNote,
        boolean selfApproved,
        Instant expiresAt,
        Integer maxRuns,
        int runsUsed
) {
}
