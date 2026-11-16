package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.dto.RunTestResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An empty data cell is explained as the row's missing value, not as an undefined environment variable. */
class DataRowMissingCellTest {

    private static RunTestResult failed(String error) {
        return new RunTestResult(false, false, null, "c", "Case", "GET", null, 200, 0, false, 0L, 3000L, false, null,
                Map.of(), Map.of(), List.of(), error, null, Map.of(), null, null, null);
    }

    @Test
    void namesTheRowAndTheColumn() {
        RunTestResult result = DataRowRun.explainMissingCell(failed("Unresolved variable {{limit}}. Define it in the environment"),
                "empty limit", Map.of("id", "7"), Set.of("id", "limit"));
        assertTrue(result.error().startsWith("Data row \"empty limit\" has no value for {{limit}}"), result.error());
    }

    @Test
    void leavesOtherErrorsAlone() {
        RunTestResult unknown = failed("Unresolved variable {{token}}. Define it in the environment");
        assertSame(unknown, DataRowRun.explainMissingCell(unknown, "r", Map.of(), Set.of("id")));
        RunTestResult other = failed("Connection refused");
        assertEquals("Connection refused", DataRowRun.explainMissingCell(other, "r", Map.of(), Set.of("id")).error());
    }
}
