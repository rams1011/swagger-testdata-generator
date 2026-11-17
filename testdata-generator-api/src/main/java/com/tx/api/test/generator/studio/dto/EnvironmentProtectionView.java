package com.tx.api.test.generator.studio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One environment's protection. {@code protected} is the admin's switch; {@code matchedTarget} is the protected
 * target its base URL falls under, which protects it whatever the switch says. {@code effective} is either.
 */
public record EnvironmentProtectionView(
        String environmentId,
        String name,
        @JsonProperty("protected") boolean protectedEnvironment,
        String matchedTarget,
        boolean effective
) {
}
