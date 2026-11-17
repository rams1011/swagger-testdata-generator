package com.tx.api.test.generator.studio.dto;

/**
 * Why production safety stopped a request ({@code shadow} false: not sent) or would have stopped it ({@code shadow}
 * true: sent, because the organization is in Shadow). {@code target} is the protected target or environment.
 */
public record SafetyPolicyNote(
        String code,
        String message,
        String ruleId,
        String target,
        String environmentId,
        boolean shadow
) {
}
