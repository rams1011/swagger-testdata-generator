package com.tx.api.test.generator.studio.safety;

import java.time.Instant;

/**
 * Permission for one case, at one published revision, to send writes to one protected environment until a date,
 * for a number of runs (null = unlimited). {@code status} is requested, approved, rejected, revoked, expired, ended
 * (the case changed) or withdrawn.
 */
public record WriteApproval(
        String id,
        String orgId,
        String caseId,
        long caseRevision,
        String environmentId,
        String status,
        String reason,
        String requestedBy,
        Instant requestedAt,
        String decidedBy,
        Instant decidedAt,
        String decisionNote,
        boolean selfApproved,
        Instant expiresAt,
        Integer maxRuns,
        int runsUsed
) {
    public static final String REQUESTED = "requested";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String REVOKED = "revoked";
    public static final String EXPIRED = "expired";
    public static final String ENDED = "ended";
    public static final String WITHDRAWN = "withdrawn";

    /** Approved, not expired and with runs left. Whether it still covers the case's revision is checked separately. */
    public boolean usable(Instant now) {
        return APPROVED.equals(status) && (expiresAt == null || expiresAt.isAfter(now))
                && (maxRuns == null || runsUsed < maxRuns);
    }

    public boolean open() {
        return REQUESTED.equals(status) || APPROVED.equals(status);
    }

    WriteApproval withStatus(String nextStatus) {
        return new WriteApproval(id, orgId, caseId, caseRevision, environmentId, nextStatus, reason, requestedBy,
                requestedAt, decidedBy, decidedAt, decisionNote, selfApproved, expiresAt, maxRuns, runsUsed);
    }
}
