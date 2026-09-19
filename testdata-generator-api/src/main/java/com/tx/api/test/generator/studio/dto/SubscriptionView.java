package com.tx.api.test.generator.studio.dto;

import java.time.Instant;

public record SubscriptionView(
        String tenantId,
        String slug,
        String name,
        String ownerEmail,
        String ownerName,
        boolean ready,
        Instant createdAt,
        Instant enabledAt
) {
}
