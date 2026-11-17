package com.tx.api.test.generator.studio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tx.api.test.generator.studio.collection.StudioEnvironmentNetwork;
import com.tx.api.test.generator.studio.collection.StudioVariable;

import java.util.List;

public record EnvironmentSummary(
        String id,
        String specId,
        String name,
        String baseUrl,
        String authType,
        List<StudioVariable> variables,
        StudioEnvironmentNetwork network,
        /* Marked Protected by an organization admin (production safety). A base URL matching a protected target
           protects it too; GET /api/safety/environments says which. */
        @JsonProperty("protected") boolean protectedEnvironment
) {
}
