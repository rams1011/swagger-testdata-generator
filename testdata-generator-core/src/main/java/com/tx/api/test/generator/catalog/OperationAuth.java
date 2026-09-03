package com.tx.api.test.generator.catalog;

public record OperationAuth(
        String type,
        String schemeName,
        String apiKeyName,
        String apiKeyIn,
        String tokenUrl,
        String grantType,
        String description
) {
    public static final String NONE = "NONE";
    public static final String BASIC = "BASIC";
    public static final String BEARER = "BEARER";
    public static final String API_KEY = "API_KEY";
    public static final String OAUTH2 = "OAUTH2";

    public static OperationAuth none() {
        return new OperationAuth(NONE, null, null, null, null, null, null);
    }
}
