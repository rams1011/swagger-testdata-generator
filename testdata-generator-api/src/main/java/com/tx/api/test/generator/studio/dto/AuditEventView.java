package com.tx.api.test.generator.studio.dto;

import java.time.Instant;

public record AuditEventView(
        String eventId,
        String specId,
        String resourceType,
        String resourceId,
        String action,
        String actorLabel,
        Instant occurredAt,
        String detail
) {
}
