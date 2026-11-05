package com.tx.api.test.generator.studio.dto;

import java.util.Map;

/** {@code secrets} holds values for secret variables, entered in the browser and never stored. */
public record RunTestRequest(
        String caseId,
        SaveTestDataRequest testCase,
        RunAuth auth,
        String environmentId,
        Map<String, String> secrets
) {
    public RunTestRequest(String caseId, SaveTestDataRequest testCase, RunAuth auth, String environmentId) {
        this(caseId, testCase, auth, environmentId, null);
    }

    @Override
    public String toString() {
        return "RunTestRequest[caseId=" + caseId
                + ", testCase=" + (testCase == null ? "null" : "SaveTestDataRequest")
                + ", auth=" + auth
                + ", environmentId=" + environmentId
                + ", secrets=" + (secrets == null ? 0 : secrets.size()) + "]";
    }
}
