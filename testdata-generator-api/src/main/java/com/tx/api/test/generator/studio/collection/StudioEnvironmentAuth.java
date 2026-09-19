package com.tx.api.test.generator.studio.collection;

public record StudioEnvironmentAuth(
        String type,
        String token,
        String username,
        String password,
        String apiKey,
        String apiKeyName,
        String apiKeyIn,
        String clientId,
        String clientSecret,
        String tokenUrl,
        String grantType
) {
}
