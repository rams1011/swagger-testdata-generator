package com.tx.api.test.generator.studio.tenant;

public final class OidcAccounts {

    public static final String EXTERNAL_PASSWORD_MARKER = "oidc$external";

    private OidcAccounts() {
    }

    public static boolean isOidcManaged(String passwordHash) {
        return EXTERNAL_PASSWORD_MARKER.equals(passwordHash);
    }

    public static boolean isExternalIdentity(String passwordHash) {
        return LdapAccounts.isDirectoryManaged(passwordHash) || isOidcManaged(passwordHash);
    }
}
