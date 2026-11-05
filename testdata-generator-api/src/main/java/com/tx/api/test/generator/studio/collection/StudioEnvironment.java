package com.tx.api.test.generator.studio.collection;

import java.util.List;
import java.util.Map;

public record StudioEnvironment(
        String format,
        int version,
        String name,
        Map<String, String> values,
        StudioEnvironmentAuth auth,
        Map<String, StudioEnvironmentCaseOverlay> cases,
        List<StudioVariable> variables
) {
    public StudioEnvironment(String format, int version, String name, Map<String, String> values,
                             StudioEnvironmentAuth auth, Map<String, StudioEnvironmentCaseOverlay> cases) {
        this(format, version, name, values, auth, cases, List.of());
    }

    public Map<String, StudioEnvironmentCaseOverlay> caseOverlays() {
        return cases == null ? Map.of() : cases;
    }

    public List<StudioVariable> variableList() {
        return variables == null ? List.of() : variables;
    }
}
