package com.tx.api.test.generator.studio.collection;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * The optional top-level {@code safety} block of an exported environment file (docs/PRODUCTION_SAFETY.md §4.8): a
 * read-only copy of the organization's production safety policy for api-automation, which makes no call to SpecPact.
 * {@code protected} is this environment's protection (the admin's switch, or a base URL under a protected target).
 * Import ignores the block. Target notes and rule reasons are left out.
 */
public record StudioEnvironmentSafety(
        @SerializedName("protected") boolean protectedEnvironment,
        String mode,
        List<Target> targets,
        List<Rule> rules,
        Limits limits,
        long revision,
        String exportedAt
) {
    /** {@code pattern}: a host, {@code *.host} pattern or CIDR range; empty {@code ports} means any port. */
    public record Target(String kind, String pattern, List<Integer> ports) {
    }

    /** {@code target}: the target's pattern, or null for every protected target. */
    public record Rule(String effect, String protocol, String method, String pathTemplate, String operationName,
                       String target, String expiresAt) {
    }

    public record Limits(int maxRequestsPerRun, int maxRequestsPerMinute, int maxWriteDataRows) {
    }
}
