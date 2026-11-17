package com.tx.api.test.generator.studio.dto;

import java.time.Instant;

/** {@code waitingForSecondAdmin}: the organization requires a second admin and nobody has confirmed it yet. */
public record EmergencyAccessView(
        String id,
        String environmentId,
        String environmentName,
        String reason,
        String grantedBy,
        String grantedByLabel,
        String confirmedByLabel,
        Instant startsAt,
        Instant endsAt,
        Integer maxRequests,
        int requestsUsed,
        boolean waitingForSecondAdmin,
        boolean active
) {
}
