package com.tx.api.test.generator.studio.dto;

import com.tx.api.test.generator.studio.safety.SafetyMode;

/**
 * What any signed-in member may know about production safety: whether it's on, the mode and limits, and whether
 * they can manage it. No target notes or rule reasons. {@code enabled} is false while the server switch is off.
 */
public record SafetySummaryView(
        boolean enabled,
        SafetyMode mode,
        int maxRequestsPerRun,
        int maxRequestsPerMinute,
        int maxWriteDataRows,
        int targetCount,
        boolean canManage
) {
}
