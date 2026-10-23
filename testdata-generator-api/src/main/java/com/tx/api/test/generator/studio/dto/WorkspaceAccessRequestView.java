package com.tx.api.test.generator.studio.dto;

import java.time.Instant;

public record WorkspaceAccessRequestView(
        String requestId,
        String workspaceId,
        String workspaceName,
        String accountId,
        String email,
        String name,
        String message,
        Instant createdAt
) {
}
