package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.dto.RunTestResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a result is filed in run history, which the History page's Outcome filter reads. */
class RunOutcomeTest {

    @Test
    void aRequestThatGotNoResponseIsAnError() {
        assertEquals("error", RunHistoryService.outcome(result(false, 0, "Unresolved variable {{token}}", null)));
        assertEquals("failed", RunHistoryService.outcome(result(false, 404, null, null)));
        assertEquals("passed", RunHistoryService.outcome(result(true, 200, null, null)));
    }

    @Test
    void dataRowsAreAnErrorOnlyWhenNoRowGotAResponse() {
        RunTestResult unsent = result(false, 0, "Unresolved variable {{token}}", null);
        RunTestResult answered = result(false, 400, "Expected 201", null);
        assertEquals("error", RunHistoryService.outcome(
                result(false, 0, "1 of 1 data rows failed: row", List.of(unsent))));
        assertEquals("failed", RunHistoryService.outcome(
                result(false, 0, "2 of 2 data rows failed: rows", List.of(unsent, answered))));
    }

    private static RunTestResult result(boolean passed, int status, String error, List<RunTestResult> rows) {
        return new RunTestResult(passed, false, null, "c1", "Case", "POST", "https://x/users", 201, status,
                status == 201, 5, 0, false, "{}", Map.of(), Map.of(), List.of(), error, null, Map.of(), null, null, rows);
    }
}
