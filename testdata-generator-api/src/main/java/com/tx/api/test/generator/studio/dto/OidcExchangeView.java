package com.tx.api.test.generator.studio.dto;

import com.tx.api.test.generator.studio.dto.AuthSessionView;

public record OidcExchangeView(
        boolean needsRegistration,
        String email,
        String displayName,
        AuthSessionView session
) {
}
