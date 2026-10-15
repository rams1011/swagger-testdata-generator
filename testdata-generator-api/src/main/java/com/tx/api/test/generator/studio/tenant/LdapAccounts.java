package com.tx.api.test.generator.studio.tenant;

public final class LdapAccounts {

    /** Stored in td_account when the directory owns the password. Never matches {@link PasswordHasher}. */
    public static final String EXTERNAL_PASSWORD_MARKER = "ldap$external";

    private LdapAccounts() {
    }

    public static boolean isDirectoryManaged(String passwordHash) {
        return EXTERNAL_PASSWORD_MARKER.equals(passwordHash);
    }
}
