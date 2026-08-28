package com.tx.api.test.generator.studio.dto;

public record RunTestRequest(
        String caseId,
        SaveTestDataRequest testCase,
        RunAuth auth
) {
    @Override
    public String toString() {
        return "RunTestRequest[caseId=" + caseId
                + ", testCase=" + (testCase == null ? "null" : "SaveTestDataRequest")
                + ", auth=" + auth + "]";
    }
}
