package com.tx.api.test.generator.studio.config;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

public enum AuthProvider {
    LOCAL,
    LDAP;

    public static AuthProvider from(String raw) {
        if (StringUtils.isBlank(raw)) {
            return LOCAL;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("ldap".equals(value) || "directory".equals(value)) {
            return LDAP;
        }
        if ("local".equals(value) || "password".equals(value) || "native".equals(value)) {
            return LOCAL;
        }
        throw new IllegalArgumentException("testdata.auth.provider must be local or ldap");
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
