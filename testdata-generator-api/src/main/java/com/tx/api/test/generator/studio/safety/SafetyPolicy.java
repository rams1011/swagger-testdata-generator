package com.tx.api.test.generator.studio.safety;

import java.time.Instant;
import java.util.List;

/**
 * An organization's production safety settings, targets and rules. {@code stored} is false for an organization
 * created after migration V8 that has not changed anything yet: it starts in Shadow (existing organizations got an
 * Off row from the migration).
 */
public record SafetyPolicy(
        String orgId,
        SafetyMode mode,
        int maxRequestsPerRun,
        int maxRequestsPerMinute,
        int maxWriteDataRows,
        boolean requireSecondAdmin,
        SelfApproval selfApproval,
        long revision,
        String updatedBy,
        Instant updatedAt,
        boolean stored,
        List<ProtectedTarget> targets,
        List<OperationRule> rules
) {
    public static final int DEFAULT_MAX_REQUESTS_PER_RUN = 200;
    public static final int DEFAULT_MAX_REQUESTS_PER_MINUTE = 60;
    public static final int DEFAULT_MAX_WRITE_DATA_ROWS = 1;
    public static final int MAX_TARGETS = 200;
    public static final int MAX_RULES = 500;

    public SafetyPolicy {
        targets = targets == null ? List.of() : List.copyOf(targets);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** What an organization without a row gets: Shadow and the default limits. */
    public static SafetyPolicy defaults(String orgId) {
        return new SafetyPolicy(orgId, SafetyMode.SHADOW, DEFAULT_MAX_REQUESTS_PER_RUN, DEFAULT_MAX_REQUESTS_PER_MINUTE,
                DEFAULT_MAX_WRITE_DATA_ROWS, false, SelfApproval.ALLOWED, 0, null, null, false, List.of(), List.of());
    }

    SafetyPolicy withSettings(SafetyMode nextMode, int perRun, int perMinute, int writeRows, boolean secondAdmin,
                              SelfApproval nextSelfApproval) {
        return new SafetyPolicy(orgId, nextMode, perRun, perMinute, writeRows, secondAdmin, nextSelfApproval,
                revision, updatedBy, updatedAt, stored, targets, rules);
    }

    SafetyPolicy withTargetsAndRules(List<ProtectedTarget> nextTargets, List<OperationRule> nextRules) {
        return new SafetyPolicy(orgId, mode, maxRequestsPerRun, maxRequestsPerMinute, maxWriteDataRows,
                requireSecondAdmin, selfApproval, revision, updatedBy, updatedAt, stored, nextTargets, nextRules);
    }
}
