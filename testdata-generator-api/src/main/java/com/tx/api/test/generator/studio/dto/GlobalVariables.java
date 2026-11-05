package com.tx.api.test.generator.studio.dto;

import com.tx.api.test.generator.studio.collection.StudioVariable;

import java.util.List;

/** Workspace-wide variables shared by every spec and environment. Secret entries never carry a value. */
public record GlobalVariables(List<StudioVariable> variables) {

    public List<StudioVariable> variableList() {
        return variables == null ? List.of() : variables;
    }
}
