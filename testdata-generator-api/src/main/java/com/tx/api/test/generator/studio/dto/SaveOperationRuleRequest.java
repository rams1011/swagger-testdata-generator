package com.tx.api.test.generator.studio.dto;

import java.time.Instant;

/**
 * REST: {@code httpMethod} and {@code pathTemplate}. GraphQL: {@code operationName}. gRPC: {@code operationName}
 * as {@code package.Service/Method}. No {@code targetId} = every protected target.
 */
public record SaveOperationRuleRequest(
        String effect,
        String protocol,
        String httpMethod,
        String pathTemplate,
        String operationName,
        String targetId,
        String reason,
        Instant expiresAt
) {
}
