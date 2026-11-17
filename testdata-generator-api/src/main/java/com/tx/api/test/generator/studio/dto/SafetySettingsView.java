package com.tx.api.test.generator.studio.dto;

import com.tx.api.test.generator.studio.safety.OperationRule;
import com.tx.api.test.generator.studio.safety.ProtectedTarget;
import com.tx.api.test.generator.studio.safety.SafetyMode;
import com.tx.api.test.generator.studio.safety.SelfApproval;

import java.time.Instant;
import java.util.List;

/**
 * Settings → Production safety, for organization owners and admins. {@code notice} says what changed on its own,
 * such as the first protected target moving the organization from Off to Shadow.
 */
public record SafetySettingsView(
        String orgId,
        SafetyMode mode,
        int maxRequestsPerRun,
        int maxRequestsPerMinute,
        int maxWriteDataRows,
        boolean requireSecondAdmin,
        SelfApproval selfApproval,
        long revision,
        Instant updatedAt,
        List<ProtectedTarget> targets,
        List<OperationRule> rules,
        String notice
) {
}
