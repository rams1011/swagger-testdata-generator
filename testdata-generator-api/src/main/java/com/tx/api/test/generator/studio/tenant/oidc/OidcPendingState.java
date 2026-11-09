package com.tx.api.test.generator.studio.tenant.oidc;

import java.time.Instant;

public record OidcPendingState(
        String state,
        String codeVerifier,
        String nonce,
        String intent,
        String returnTo,
        Instant expiresAt
) {
}
