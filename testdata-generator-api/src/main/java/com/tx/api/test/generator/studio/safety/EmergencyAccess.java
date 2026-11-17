package com.tx.api.test.generator.studio.safety;

import java.time.Instant;

/**
 * Time-boxed permission for every write from one protected environment, with a required reason. When the
 * organization requires a second admin, it only works once another admin confirms it ({@code confirmedBy}).
 */
public record EmergencyAccess(
        String id,
        String orgId,
        String environmentId,
        String reason,
        String grantedBy,
        String confirmedBy,
        Instant startsAt,
        Instant endsAt,
        Integer maxRequests,
        int requestsUsed,
        String endedBy,
        Instant endedAt
) {
    /** Running now: started, not ended, not past its end, requests left, and confirmed when that's required. */
    public boolean active(Instant now, boolean secondAdminRequired) {
        return endedAt == null && !startsAt.isAfter(now) && endsAt.isAfter(now)
                && (maxRequests == null || requestsUsed < maxRequests)
                && (!secondAdminRequired || confirmedBy != null);
    }
}
