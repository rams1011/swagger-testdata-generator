package com.tx.api.test.generator.studio.model;

/**
 * Publishing a case into Run plan, export, and CI is a review step when sign-in is on.
 * The local edition has no roles, so the only user can publish.
 */
public final class PublishPolicy {

    private PublishPolicy() {
    }

    public static boolean allows(boolean authEnabled, String role) {
        if (!authEnabled) {
            return true;
        }
        return "OWNER".equals(role) || "ADMIN".equals(role);
    }
}
