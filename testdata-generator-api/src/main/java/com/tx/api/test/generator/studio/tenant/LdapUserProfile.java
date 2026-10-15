package com.tx.api.test.generator.studio.tenant;

import java.util.List;

public record LdapUserProfile(
        String principalEmail,
        String displayName,
        String mail,
        List<String> groupDns
) {
    public String studioEmail() {
        if (mail != null && !mail.isBlank()) {
            return mail.trim().toLowerCase(java.util.Locale.ROOT);
        }
        return principalEmail;
    }
}
