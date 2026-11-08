package com.tx.api.test.generator.studio.collection;

import java.util.List;

public record StudioCollection(
        String format,
        int version,
        StudioCollectionSpec spec,
        StudioCollectionPlan plan,
        List<StudioCollectionCase> cases,
        List<StudioVariable> variables
) {

    public StudioCollection(String format, int version, StudioCollectionSpec spec, StudioCollectionPlan plan,
                            List<StudioCollectionCase> cases) {
        this(format, version, spec, plan, cases, null);
    }

    /** Collection variables; secrets carry {@code ${STUDIO_VAR_NAME}}. Null in files exported before they existed. */
    public List<StudioVariable> variableList() {
        return variables == null ? List.of() : variables;
    }
}
