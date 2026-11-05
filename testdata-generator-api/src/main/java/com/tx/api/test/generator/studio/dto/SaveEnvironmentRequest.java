package com.tx.api.test.generator.studio.dto;

import com.tx.api.test.generator.studio.collection.StudioEnvironmentNetwork;
import com.tx.api.test.generator.studio.collection.StudioVariable;

import java.util.List;

/**
 * A null {@code variables} list on update keeps the environment's current variables; a null {@code network} keeps
 * its connection settings.
 */
public record SaveEnvironmentRequest(
        String name,
        String baseUrl,
        String authType,
        List<StudioVariable> variables,
        StudioEnvironmentNetwork network
) {
    public SaveEnvironmentRequest(String name, String baseUrl, String authType) {
        this(name, baseUrl, authType, null, null);
    }

    public SaveEnvironmentRequest(String name, String baseUrl, String authType, List<StudioVariable> variables) {
        this(name, baseUrl, authType, variables, null);
    }
}
