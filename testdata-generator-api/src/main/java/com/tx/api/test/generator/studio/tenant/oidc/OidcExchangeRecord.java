package com.tx.api.test.generator.studio.tenant.oidc;

import java.time.Instant;

public record OidcExchangeRecord(
        String exchangeId,
        String intent,
        String email,
        String displayName,
        String oidcIssuer,
        String oidcSubject,
        boolean needsRegistration,
        Instant expiresAt
) {
}
