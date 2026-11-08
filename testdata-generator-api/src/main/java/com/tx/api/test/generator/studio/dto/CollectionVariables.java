package com.tx.api.test.generator.studio.dto;

import com.tx.api.test.generator.studio.collection.StudioVariable;

import java.util.List;

/** Variables of one collection, shared by all its environments and cases. Secret entries never carry a value. */
public record CollectionVariables(List<StudioVariable> variables) {

    public List<StudioVariable> variableList() {
        return variables == null ? List.of() : variables;
    }
}
