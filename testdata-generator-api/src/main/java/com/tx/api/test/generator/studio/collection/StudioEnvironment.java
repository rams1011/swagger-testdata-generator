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
        List<StudioVariable> variables,
        /** Connection settings (redirects, TLS, proxy, timeout); null means defaults. */
        StudioEnvironmentNetwork network
) {
    public StudioEnvironment(String format, int version, String name, Map<String, String> values,
                             StudioEnvironmentAuth auth, Map<String, StudioEnvironmentCaseOverlay> cases) {
        this(format, version, name, values, auth, cases, List.of());
    }

    public StudioEnvironment(String format, int version, String name, Map<String, String> values,
                             StudioEnvironmentAuth auth, Map<String, StudioEnvironmentCaseOverlay> cases,
                             List<StudioVariable> variables) {
        this(format, version, name, values, auth, cases, variables, null);
    }

    public StudioEnvironment withNetwork(StudioEnvironmentNetwork next) {
        return new StudioEnvironment(format, version, name, values, auth, cases, variables,
                StudioEnvironmentNetwork.normalize(next));
    }

    public Map<String, StudioEnvironmentCaseOverlay> caseOverlays() {
        return cases == null ? Map.of() : cases;
    }

    public List<StudioVariable> variableList() {
        return variables == null ? List.of() : variables;
    }
}
