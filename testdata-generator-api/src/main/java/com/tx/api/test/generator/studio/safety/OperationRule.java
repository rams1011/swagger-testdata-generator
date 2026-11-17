package com.tx.api.test.generator.studio.safety;

import java.time.Instant;

/**
 * An admin exception to read/write classification on protected targets. REST rules match a method and a path
 * template; GraphQL rules an operation name; gRPC rules {@code package.Service/Method}. No target = every target.
 */
public record OperationRule(
        String id,
        RuleEffect effect,
        RuleProtocol protocol,
        String httpMethod,
        String pathTemplate,
        String operationName,
        String targetId,
        String reason,
        Instant expiresAt,
        String createdBy,
        Instant createdAt
) {
    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Two rules that match the same thing on the same target, whatever their effect or reason. */
    boolean sameMatch(OperationRule other) {
        return protocol == other.protocol
                && java.util.Objects.equals(httpMethod, other.httpMethod)
                && java.util.Objects.equals(pathTemplate, other.pathTemplate)
                && java.util.Objects.equals(operationName, other.operationName)
                && java.util.Objects.equals(targetId, other.targetId);
    }
}
