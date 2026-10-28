package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanRunTest {

    @Test
    void failureSkipsEveryLaterRunnableCase() {
        StudioTestCase login = named("login");
        StudioTestCase create = named("create");
        StudioTestCase get = named("get");
        List<String> sent = new ArrayList<>();

        PlanRun.Outcome outcome = PlanRun.execute(List.of(login, create, get), new PlanRun.Actions() {
            @Override
            public boolean runnable(StudioTestCase testCase) {
                return true;
            }

            @Override
            public RunTestResult execute(StudioTestCase testCase) {
                sent.add(testCase.getId());
                return result(testCase, !"login".equals(testCase.getId()));
            }

            @Override
            public RunTestResult skip(StudioTestCase testCase, String reason) {
                return skipped(testCase, reason);
            }
        });

        assertEquals(List.of("login"), sent);
        assertEquals(0, outcome.passed());
        assertEquals(1, outcome.failed());
        assertEquals(2, outcome.skipped());
        assertTrue(outcome.results().get(1).skipped());
        assertEquals("Stopped because 'Login' failed", outcome.results().get(1).skipReason());
        assertEquals("Stopped because 'Login' failed", outcome.results().get(2).skipReason());
    }

    @Test
    void disabledCasesStaySkippedAndDoNotStopThePlan() {
        StudioTestCase first = named("first");
        StudioTestCase disabled = named("disabled");
        StudioTestCase last = named("last");
        List<String> sent = new ArrayList<>();

        PlanRun.Outcome outcome = PlanRun.execute(List.of(first, disabled, last), new PlanRun.Actions() {
            @Override
            public boolean runnable(StudioTestCase testCase) {
                return !"disabled".equals(testCase.getId());
            }

            @Override
            public RunTestResult execute(StudioTestCase testCase) {
                sent.add(testCase.getId());
                return result(testCase, true);
            }

            @Override
            public RunTestResult skip(StudioTestCase testCase, String reason) {
                return skipped(testCase, reason);
            }
        });

        assertEquals(List.of("first", "last"), sent);
        assertEquals(2, outcome.passed());
        assertEquals(0, outcome.failed());
        assertEquals(1, outcome.skipped());
        assertEquals("already out", outcome.results().get(1).skipReason());
    }

    @Test
    void blankFailureNameUsesUntitled() {
        assertEquals("Stopped because 'Untitled case' failed", PlanRun.stopReason("  "));
    }

    private static StudioTestCase named(String id) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setId(id);
        testCase.setTestName("login".equals(id) ? "Login" : id);
        testCase.setMethod("GET");
        testCase.setEndpoint("/" + id);
        return testCase;
    }

    private static RunTestResult result(StudioTestCase testCase, boolean passed) {
        return new RunTestResult(
                passed,
                false,
                null,
                testCase.getId(),
                testCase.getTestName(),
                testCase.getMethod(),
                testCase.getEndpoint(),
                200,
                passed ? 200 : 500,
                passed,
                10,
                3000,
                false,
                null,
                Map.of(),
                Map.of(),
                List.of(),
                passed ? null : "status",
                null,
                Map.of());
    }

    private static RunTestResult skipped(StudioTestCase testCase, String reason) {
        return new RunTestResult(
                false,
                true,
                reason == null ? "already out" : reason,
                testCase.getId(),
                testCase.getTestName(),
                testCase.getMethod(),
                testCase.getEndpoint(),
                200,
                0,
                false,
                0,
                3000,
                false,
                null,
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                Map.of());
    }
}
