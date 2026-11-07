package com.tx.api.test.generator.studio.dto;

public record RestoreCaseVersionRequest(
        String expectedUpdatedAt,
        String expectedDraftUpdatedAt,
        String environmentId
) {
}
