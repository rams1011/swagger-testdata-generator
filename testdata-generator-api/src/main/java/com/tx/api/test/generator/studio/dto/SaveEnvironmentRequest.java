package com.tx.api.test.generator.studio.dto;

import com.tx.api.test.generator.studio.collection.StudioVariable;

import java.util.List;

/** A null {@code variables} list on update keeps the environment's current variables. */
public record SaveEnvironmentRequest(
        String name,
        String baseUrl,
        String authType,
        List<StudioVariable> variables
) {
    public SaveEnvironmentRequest(String name, String baseUrl, String authType) {
        this(name, baseUrl, authType, null);
    }
}
