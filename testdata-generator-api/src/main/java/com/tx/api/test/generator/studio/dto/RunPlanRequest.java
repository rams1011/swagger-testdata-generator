package com.tx.api.test.generator.studio.dto;

import java.util.Map;

/**
 * {@code secrets} holds values for secret variables, entered in the browser and never stored.
 * {@code continueOnFailure} keeps running later cases after a failure (default: stop at the first failure).
 */
public record RunPlanRequest(
        String specId,
        String planId,
        RunAuth auth,
        String environmentId,
        Map<String, String> secrets,
        Boolean continueOnFailure
) {
    public RunPlanRequest(String specId, String planId, RunAuth auth, String environmentId) {
        this(specId, planId, auth, environmentId, null, null);
    }

    public RunPlanRequest(String specId, String planId, RunAuth auth, String environmentId,
                          Map<String, String> secrets) {
        this(specId, planId, auth, environmentId, secrets, null);
    }

    public boolean continuesOnFailure() {
        return Boolean.TRUE.equals(continueOnFailure);
    }

    @Override
    public String toString() {
        return "RunPlanRequest[specId=" + specId + ", planId=" + planId + ", auth=" + auth
                + ", environmentId=" + environmentId
                + ", secrets=" + (secrets == null ? 0 : secrets.size())
                + ", continueOnFailure=" + continuesOnFailure() + "]";
    }
}
