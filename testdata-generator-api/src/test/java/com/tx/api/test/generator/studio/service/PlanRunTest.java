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
    void continueOnFailureRunsLaterCasesAndSkipsOnlyDependents() {
        StudioTestCase login = named("login");
        StudioTestCase create = named("create");
        create.setTestName("Create user");
        create.setExtracts("userId=$.id");
        StudioTestCase get = named("get");
        get.setEndpoint("/users/{{userId}}");
        StudioTestCase health = named("health");
        StudioTestCase token = named("token");
        token.setHeaders(Map.of("Authorization", "Bearer {{token}}"));
        List<String> sent = new ArrayList<>();

        PlanRun.Outcome outcome = PlanRun.execute(List.of(login, create, get, health, token), new PlanRun.Actions() {
            @Override
            public boolean runnable(StudioTestCase testCase) {
                return true;
            }

            @Override
            public RunTestResult execute(StudioTestCase testCase) {
                sent.add(testCase.getId());
                return result(testCase, !"create".equals(testCase.getId()));
            }

            @Override
            public RunTestResult skip(StudioTestCase testCase, String reason) {
                return skipped(testCase, reason);
            }

            @Override
            public boolean defined(String name) {
                return "token".equals(name);
            }
        }, true);

        assertEquals(List.of("login", "create", "health", "token"), sent);
        assertEquals(3, outcome.passed());
        assertEquals(1, outcome.failed());
        assertEquals(1, outcome.skipped());
        assertTrue(outcome.results().get(2).skipped());
        assertEquals("Skipped because {{userId}} comes from 'Create user', which failed",
                outcome.results().get(2).skipReason());
    }

    @Test
    void continueOnFailureRunsADependentWhenTheVariableIsAlreadyDefined() {
        StudioTestCase create = named("create");
        create.setExtracts("userId=$.id");
        StudioTestCase get = named("get");
        get.setEndpoint("/users/{{userId}}");
        List<String> sent = new ArrayList<>();

        PlanRun.execute(List.of(create, get), new PlanRun.Actions() {
            @Override
            public boolean runnable(StudioTestCase testCase) {
                return true;
            }

            @Override
            public RunTestResult execute(StudioTestCase testCase) {
                sent.add(testCase.getId());
                return result(testCase, false);
            }

            @Override
            public RunTestResult skip(StudioTestCase testCase, String reason) {
                return skipped(testCase, reason);
            }

            @Override
            public boolean defined(String name) {
                return true;
            }
        }, true);

        assertEquals(List.of("create", "get"), sent);
    }

    @Test
    void blankFailureNameUsesUntitled() {
        assertEquals("Stopped because 'Untitled case' failed", PlanRun.stopReason("  "));
    }

    @Test
    void retriesAFailedCaseAndWaitsTheDelayBetweenRequests() {
        StudioTestCase flaky = named("flaky");
        StudioTestCase next = named("next");
        List<String> events = new ArrayList<>();
        int[] calls = {0};

        PlanRun.Outcome outcome = PlanRun.execute(List.of(flaky, next), new PlanRun.Actions() {
            @Override
            public boolean runnable(StudioTestCase testCase) {
                return true;
            }

            @Override
            public RunTestResult execute(StudioTestCase testCase) {
                events.add("send " + testCase.getId());
                boolean passed = !"flaky".equals(testCase.getId()) || ++calls[0] >= 3;
                return result(testCase, passed);
            }

            @Override
            public RunTestResult skip(StudioTestCase testCase, String reason) {
                return skipped(testCase, reason);
            }

            @Override
            public void pause(long millis) {
                events.add("wait " + millis);
            }
        }, new PlanRun.Options(false, 250L, 3));

        assertEquals(List.of("send flaky", "wait 250", "send flaky", "wait 250", "send flaky", "wait 250", "send next"),
                events);
        assertEquals(2, outcome.passed());
        var attempts = outcome.results().get(0).assertions().stream()
                .filter(check -> "attempts".equals(check.name())).findFirst().orElseThrow();
        assertEquals("3", attempts.actual());
        assertEquals("up to 4", attempts.expected());
        assertTrue(outcome.results().get(1).assertions().stream().noneMatch(check -> "attempts".equals(check.name())));
    }

    @Test
    void retriesStopAtTheLimitAndTheFailureStillStopsThePlan() {
        StudioTestCase broken = named("broken");
        StudioTestCase later = named("later");
        List<String> sent = new ArrayList<>();

        PlanRun.Outcome outcome = PlanRun.execute(List.of(broken, later), new PlanRun.Actions() {
            @Override
            public boolean runnable(StudioTestCase testCase) {
                return true;
            }

            @Override
            public RunTestResult execute(StudioTestCase testCase) {
                sent.add(testCase.getId());
                return result(testCase, false);
            }

            @Override
            public RunTestResult skip(StudioTestCase testCase, String reason) {
                return skipped(testCase, reason);
            }

            @Override
            public void pause(long millis) {
            }
        }, new PlanRun.Options(false, 0L, 2));

        assertEquals(List.of("broken", "broken", "broken"), sent);
        assertEquals(1, outcome.failed());
        assertEquals(1, outcome.skipped());
        assertEquals("3", outcome.results().get(0).assertions().get(0).actual());
    }

    @Test
    void runPlanRequestClampsDelayAndRetries() {
        var request = new com.tx.api.test.generator.studio.dto.RunPlanRequest("s", "p", null, null, null, null,
                999_999L, 99);
        assertEquals(60_000L, request.delayMillis());
        assertEquals(5, request.retryCount());
        var none = new com.tx.api.test.generator.studio.dto.RunPlanRequest("s", "p", null, null);
        assertEquals(0L, none.delayMillis());
        assertEquals(0, none.retryCount());
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
