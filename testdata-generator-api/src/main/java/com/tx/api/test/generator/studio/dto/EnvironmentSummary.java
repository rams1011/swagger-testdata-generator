package com.tx.api.test.generator.studio.dto;

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
        StudioEnvironmentNetwork network
) {
}
