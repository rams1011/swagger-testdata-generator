package com.tx.api.test.generator.studio.dto;

public record CopyEnvironmentOverlayRequest(
        String fromEnvironmentId,
        String toEnvironmentId
) {
}
