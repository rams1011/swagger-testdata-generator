package com.tx.api.test.generator.studio.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseLifecycleTest {

    @Test
    void disabledPublishedCasesStayOutOfExport() {
        StudioTestCase published = caseWith("READY", "Y");
        StudioTestCase disabled = caseWith("READY", "N");
        StudioTestCase draft = caseWith("DRAFT", "Y");
        StudioTestCase blankEnabled = caseWith("READY", null);

        assertTrue(CaseLifecycle.isExportable(published));
        assertTrue(CaseLifecycle.isExportable(blankEnabled));
        assertFalse(CaseLifecycle.isExportable(disabled));
        assertFalse(CaseLifecycle.isExportable(draft));
    }

    private static StudioTestCase caseWith(String lifecycle, String enabled) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setLifecycle(lifecycle);
        testCase.setEnabled(enabled);
        return testCase;
    }
}
