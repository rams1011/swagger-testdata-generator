package com.tx.api.test.generator.studio.dto;

public record RestoreCaseVersionRequest(
        Long expectedRevision,
        Long expectedDraftRevision,
        String environmentId
) {
}
