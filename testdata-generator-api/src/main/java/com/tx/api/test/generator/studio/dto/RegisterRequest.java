package com.tx.api.test.generator.studio.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * SaaS self-service signup. Organization (billing/roster) and first workspace are separate.
 * {@code workspace} alone is legacy: it sets the organization name; the first workspace defaults to {@code Default}.
 */
public record RegisterRequest(
        String email,
        String password,
        String name,
        String organizationName,
        String workspaceName,
        @JsonAlias("workspace") String legacyWorkspace
) {
    /** Pre–org/workspace split clients passed one {@code workspace} field for both. */
    public RegisterRequest(String email, String password, String name, String legacyWorkspace) {
        this(email, password, name, null, null, legacyWorkspace);
    }
}
