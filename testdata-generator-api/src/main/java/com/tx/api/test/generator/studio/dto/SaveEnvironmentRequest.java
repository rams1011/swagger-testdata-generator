package com.tx.api.test.generator.studio.dto;

public record SaveEnvironmentRequest(
        String name,
        String baseUrl,
        String authType
) {
}
