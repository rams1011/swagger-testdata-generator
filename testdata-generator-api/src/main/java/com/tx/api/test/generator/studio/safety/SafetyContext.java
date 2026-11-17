package com.tx.api.test.generator.studio.safety;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One run's production safety state: the organization, whether the selected environment is protected, and what the
 * run has sent to protected targets so far. Made once per Send, Run or Run plan, so prerequisites, data rows and
 * retries share it. Runs are sent one request at a time, so it needs no locking.
 */
public final class SafetyContext {

    private static final SafetyContext NONE = new SafetyContext(null, null, null, false);

    private final String orgId;
    private final String environmentId;
    private final String environmentName;
    private final boolean environmentProtected;
    private int protectedRequests;
    private final Map<String, Integer> writeRows = new HashMap<>();
    private final Set<String> noRetry = new HashSet<>();

    SafetyContext(String orgId, String environmentId, String environmentName, boolean environmentProtected) {
        this.orgId = orgId;
        this.environmentId = environmentId;
        this.environmentName = environmentName;
        this.environmentProtected = environmentProtected;
    }

    /** No organization (login off, or production safety off): every request is allowed. */
    public static SafetyContext none() {
        return NONE;
    }

    public String orgId() {
        return orgId;
    }

    public String environmentId() {
        return environmentId;
    }

    public String environmentName() {
        return environmentName;
    }

    public boolean environmentProtected() {
        return environmentProtected;
    }

    /** False once a write reached a protected target in this run: a retry would send it again. */
    public boolean mayRetry(String caseId) {
        return caseId == null || !noRetry.contains(caseId);
    }

    int countProtectedRequest() {
        return ++protectedRequests;
    }

    int countWriteRow(String caseId) {
        return writeRows.merge(caseId == null ? "" : caseId, 1, Integer::sum);
    }

    void markNoRetry(String caseId) {
        if (caseId != null && this != NONE) {
            noRetry.add(caseId);
        }
    }
}
