package com.tx.api.test.generator.studio.dto;

import java.util.Map;

/** {@code secrets} holds values for secret variables, entered in the browser and never stored. */
public record RunTestRequest(
        String caseId,
        SaveTestDataRequest testCase,
        RunAuth auth,
        String environmentId,
        Map<String, String> secrets,
        /** For a case with data rows: run only this row (1-based), as Send does; null runs every row. */
        Integer dataRow,
        /** Production safety: the environment's name, typed to confirm an approved write to production. */
        String confirmEnvironment
) {
    public RunTestRequest(String caseId, SaveTestDataRequest testCase, RunAuth auth, String environmentId,
                          Map<String, String> secrets, Integer dataRow) {
        this(caseId, testCase, auth, environmentId, secrets, dataRow, null);
    }

    public RunTestRequest(String caseId, SaveTestDataRequest testCase, RunAuth auth, String environmentId) {
        this(caseId, testCase, auth, environmentId, null, null, null);
    }

    public RunTestRequest(String caseId, SaveTestDataRequest testCase, RunAuth auth, String environmentId,
                          Map<String, String> secrets) {
        this(caseId, testCase, auth, environmentId, secrets, null, null);
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
