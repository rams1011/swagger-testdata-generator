package com.tx.api.test.generator.studio.tenant;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

public final class LdapLoginNames {

    private LdapLoginNames() {
    }

    public static String normalizePrincipal(String rawLogin, String domain) {
        String domainNorm = StringUtils.trimToNull(domain);
        if (domainNorm == null) {
            throw new IllegalArgumentException("LDAP domain is required");
        }
        domainNorm = domainNorm.toLowerCase(Locale.ROOT);
        String login = StringUtils.trimToNull(rawLogin);
        if (login == null) {
            throw new IllegalArgumentException("Username is required");
        }
        int slash = login.indexOf('\\');
        if (slash >= 0 && slash < login.length() - 1) {
            login = login.substring(slash + 1);
        }
        login = login.trim();
        if (!login.contains("@")) {
            return login.toLowerCase(Locale.ROOT) + "@" + domainNorm;
        }
        String lower = login.toLowerCase(Locale.ROOT);
        if (!lower.endsWith("@" + domainNorm)) {
            throw new IllegalArgumentException("Sign in with an account from " + domainNorm);
        }
        return lower;
    }

    public static String domainToBaseDn(String domain) {
        String trimmed = StringUtils.trimToEmpty(domain).toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) {
            return "";
        }
        String[] labels = trimmed.split("\\.");
        StringBuilder builder = new StringBuilder();
        for (String label : labels) {
            if (label.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append("DC=").append(escapeDnComponent(label));
        }
        return builder.toString();
    }

    private static String escapeDnComponent(String value) {
        return value.replace("\\", "\\\\").replace(",", "\\,");
    }
}
