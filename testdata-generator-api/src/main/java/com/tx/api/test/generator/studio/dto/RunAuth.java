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
        String apiKeyIn,
        String clientId,
        String clientSecret,
        String tokenUrl,
        String grantType,
        /** OAuth scope sent with the token request (optional). */
        String scope
) {
    public RunAuth(String type, String username, String password, String token, String apiKey, String apiKeyName,
                   String apiKeyIn, String clientId, String clientSecret, String tokenUrl, String grantType) {
        this(type, username, password, token, apiKey, apiKeyName, apiKeyIn, clientId, clientSecret, tokenUrl,
                grantType, null);
    }

    /** Copy with every text field passed through {@code resolve} (variables in a case's own auth). */
    public RunAuth map(java.util.function.UnaryOperator<String> resolve) {
        return new RunAuth(type, resolve.apply(username), resolve.apply(password), resolve.apply(token),
                resolve.apply(apiKey), resolve.apply(apiKeyName), apiKeyIn, resolve.apply(clientId),
                resolve.apply(clientSecret), resolve.apply(tokenUrl), grantType, resolve.apply(scope));
    }

    public static final String NONE = "NONE";
    public static final String BASIC = "BASIC";
    public static final String BEARER = "BEARER";
    public static final String API_KEY = "API_KEY";
    public static final String OAUTH2 = "OAUTH2";

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
            case "OAUTH2", "OAUTH", "OPENID", "OIDC" -> OAUTH2;
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
