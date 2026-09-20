package com.tx.api.test.generator.studio.collection;

import java.util.Map;

public record StudioEnvironment(
        String format,
        int version,
        String name,
        Map<String, String> values,
        StudioEnvironmentAuth auth,
        Map<String, StudioEnvironmentCaseOverlay> cases
) {
    public Map<String, StudioEnvironmentCaseOverlay> caseOverlays() {
        return cases == null ? Map.of() : cases;
    }
}
