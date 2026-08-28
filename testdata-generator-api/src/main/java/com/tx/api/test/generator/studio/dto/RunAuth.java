package com.tx.api.test.generator.studio.dto;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

public record RunAuth(
        String type,
        String username,
        String password,
        String token,
        String apiKey,
        String apiKeyName,
        String apiKeyIn
) {
    public static final String NONE = "NONE";
    public static final String BASIC = "BASIC";
    public static final String BEARER = "BEARER";
    public static final String API_KEY = "API_KEY";

    public String normalizedType() {
        String raw = StringUtils.defaultIfBlank(type, NONE)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (raw) {
            case "CREDENTIALS", "BASIC_AUTH", "BASIC" -> BASIC;
            case "BEARER", "BEARER_TOKEN", "TOKEN" -> BEARER;
            case "API_KEY", "APIKEY", "KEY" -> API_KEY;
            default -> NONE;
        };
    }

    @Override
    public String toString() {
        return "RunAuth[type=" + normalizedType()
                + ", username=" + username
                + ", apiKeyName=" + apiKeyName
                + ", apiKeyIn=" + apiKeyIn + "]";
    }
}
