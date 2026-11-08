package com.tx.api.test.generator.studio.dto;

import java.util.List;
import java.util.Map;

/**
 * {@code secrets} holds values for secret variables, entered in the browser and never stored.
 * {@code continueOnFailure} keeps running later cases after a failure (default: stop at the first failure).
 * {@code delayMs} waits between requests (0..60000). {@code retries} re-sends a failed case up to that many times
 * (0..5), waiting {@code delayMs} between attempts. {@code caseIds} runs only those cases, plus earlier cases they need
 * extracted values from; null runs the whole plan.
 */
public record RunPlanRequest(
        String specId,
        String planId,
        RunAuth auth,
        String environmentId,
        Map<String, String> secrets,
        Boolean continueOnFailure,
        Long delayMs,
        Integer retries,
        List<String> caseIds
) {
    public static final long MAX_DELAY_MS = 60_000L;
    public static final int MAX_RETRIES = 5;

    public RunPlanRequest(String specId, String planId, RunAuth auth, String environmentId) {
        this(specId, planId, auth, environmentId, null, null);
    }

    public RunPlanRequest(String specId, String planId, RunAuth auth, String environmentId,
                          Map<String, String> secrets) {
        this(specId, planId, auth, environmentId, secrets, null);
    }

    public RunPlanRequest(String specId, String planId, RunAuth auth, String environmentId,
                          Map<String, String> secrets, Boolean continueOnFailure) {
        this(specId, planId, auth, environmentId, secrets, continueOnFailure, null, null);
    }

    public RunPlanRequest(String specId, String planId, RunAuth auth, String environmentId,
                          Map<String, String> secrets, Boolean continueOnFailure, Long delayMs, Integer retries) {
        this(specId, planId, auth, environmentId, secrets, continueOnFailure, delayMs, retries, null);
    }

    public long delayMillis() {
        return delayMs == null ? 0L : Math.max(0L, Math.min(MAX_DELAY_MS, delayMs));
    }

    public int retryCount() {
        return retries == null ? 0 : Math.max(0, Math.min(MAX_RETRIES, retries));
    }

    public boolean continuesOnFailure() {
        return Boolean.TRUE.equals(continueOnFailure);
    }

    @Override
    public String toString() {
        return "RunPlanRequest[specId=" + specId + ", planId=" + planId + ", auth=" + auth
                + ", environmentId=" + environmentId
                + ", secrets=" + (secrets == null ? 0 : secrets.size())
                + ", continueOnFailure=" + continuesOnFailure()
                + ", delayMs=" + delayMillis() + ", retries=" + retryCount()
                + ", caseIds=" + (caseIds == null ? "all" : caseIds.size()) + "]";
    }
}
