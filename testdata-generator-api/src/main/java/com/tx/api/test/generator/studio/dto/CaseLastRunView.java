package com.tx.api.test.generator.studio.dto;

import java.time.Instant;

/** The most recent recorded result of one case, for the Cases table's Last run column. */
public record CaseLastRunView(
        String caseId,
        String runId,
        String resultId,
        String trigger,
        String outcome,
        Integer actualStatus,
        Instant startedAt
) {
}
