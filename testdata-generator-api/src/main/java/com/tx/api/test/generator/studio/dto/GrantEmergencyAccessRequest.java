package com.tx.api.test.generator.studio.dto;

/** {@code minutes}: 15, 30 or 60. {@code maxRequests}: 1 to 1000, or null for no request limit. */
public record GrantEmergencyAccessRequest(
        String environmentId,
        Integer minutes,
        Integer maxRequests,
        String reason
) {
}
