package com.tx.api.test.generator.studio.safety;

import java.time.Instant;

/**
 * One request the safety check stopped (Enforce) or would have stopped ({@code shadow}), for the review on the
 * settings page. {@code pathTemplate} is the case's path ({@code /users/{id}}), or the URL's path for a redirect hop.
 */
public record SafetyDecision(
        String id,
        String orgId,
        Instant occurredAt,
        String environmentId,
        String caseId,
        String method,
        String pathTemplate,
        String targetId,
        String targetLabel,
        String code,
        boolean shadow
) {
    /** Requests stopped (or would-be stopped) for the same operation, target and reason, over the review's days. */
    public record Group(
            String method,
            String pathTemplate,
            String targetId,
            String targetLabel,
            String code,
            boolean shadow,
            long count,
            Instant lastSeen,
            String exampleCaseId
    ) {
    }
}
