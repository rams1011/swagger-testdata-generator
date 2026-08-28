package com.tx.api.test.generator.studio.dto;

public record RunPlanRequest(
        String specId,
        String planId,
        RunAuth auth
) {
    @Override
    public String toString() {
        return "RunPlanRequest[specId=" + specId + ", planId=" + planId + ", auth=" + auth + "]";
    }
}
