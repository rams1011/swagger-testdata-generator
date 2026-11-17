package com.tx.api.test.generator.studio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SetEnvironmentProtectionRequest(
        @JsonProperty("protected") Boolean protectedEnvironment
) {
}
