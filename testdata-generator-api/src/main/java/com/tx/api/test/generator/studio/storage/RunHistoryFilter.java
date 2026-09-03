package com.tx.api.test.generator.studio.storage;

public record RunHistoryFilter(
        String specId,
        String planId,
        String caseId,
        String outcome,
        String trigger,
        int limit
) {
}
