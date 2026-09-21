package com.tx.api.test.generator.studio.dto;

import java.time.Instant;

public record SubscriptionView(
        String orgId,
        String slug,
        String name,
        String ownerEmail,
        String ownerName,
        String plan,
        String status,
        boolean writable,
        boolean ready,
        Instant createdAt,
        Instant trialEndsAt,
        Instant currentPeriodEnd,
        int seatLimit,
        int seatsUsed,
        int workspaceLimit,
        int workspacesUsed,
        int specLimit,
        int specsUsed,
        int runLimitMonth,
        int runsUsed,
        String yearMonth
) {
}
