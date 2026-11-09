package com.tx.api.test.generator.studio.dto;

public record OidcRegisterRequest(
        String exchange,
        String organizationName,
        String workspaceName
) {
}
