package com.tx.api.test.generator.studio.dto;

/**
 * What production safety would do with a stored case in an environment, without sending anything (case editor
 * strip, PR 5). {@code state}: off, unprotected, read, shadow, draft, blocked, waiting or approved. {@code code} and
 * {@code message} explain a block; {@code confirmationText} is what to type to send an approved write.
 */
public record SafetyEvaluateView(
        boolean enabled,
        String mode,
        boolean protectedEnvironment,
        String target,
        boolean write,
        String reason,
        String state,
        String code,
        String message,
        String via,
        ApprovalView approval,
        String confirmationText
) {
}
