package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs plan cases in order. By default it stops after the first failure so later cases are not sent.
 * With continue on failure, later cases still run, except a case that needs a variable an earlier
 * failed case should have extracted: that case is skipped with the reason, instead of failing on it.
 * A delay waits between requests; retries re-send a failed case (an "attempts" check records how many it took).
 */
final class PlanRun {

    private PlanRun() {
    }

    interface Actions {
        boolean runnable(StudioTestCase testCase);

        RunTestResult execute(StudioTestCase testCase);

        /** {@code reason} is null for a case that was already out of the run (draft, disabled, or other environment). */
        RunTestResult skip(StudioTestCase testCase, String reason);

        /** True when {@code name} already has a value in the run (environment, global, secret, or earlier extract). */
        default boolean defined(String name) {
            return false;
        }

        default void pause(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    record Options(boolean continueOnFailure, long delayMs, int retries) {
        static Options of(boolean continueOnFailure) {
            return new Options(continueOnFailure, 0L, 0);
        }
    }

    record Outcome(int passed, int failed, int skipped, List<RunTestResult> results) {
    }

    static Outcome execute(List<StudioTestCase> cases, Actions actions) {
        return execute(cases, actions, false);
    }

    static Outcome execute(List<StudioTestCase> cases, Actions actions, boolean continueOnFailure) {
        return execute(cases, actions, Options.of(continueOnFailure));
    }

    static Outcome execute(List<StudioTestCase> cases, Actions actions, Options options) {
        boolean continueOnFailure = options.continueOnFailure();
        boolean sent = false;
        List<RunTestResult> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        String stopReason = null;
        Map<String, String> missingFrom = new LinkedHashMap<>();
        for (StudioTestCase testCase : cases) {
            if (!actions.runnable(testCase)) {
                results.add(actions.skip(testCase, null));
                skipped++;
                continue;
            }
            if (stopReason != null) {
                results.add(actions.skip(testCase, stopReason));
                skipped++;
                continue;
            }
            String dependency = failedDependency(testCase, missingFrom, actions);
            if (dependency != null) {
                results.add(actions.skip(testCase, dependency));
                skipped++;
                continue;
            }
            if (sent && options.delayMs() > 0) {
                actions.pause(options.delayMs());
            }
            RunTestResult result = attempt(testCase, actions, options);
            sent = true;
            results.add(result);
            if (result.passed()) {
                passed++;
            } else if (!result.skipped()) {
                failed++;
                if (continueOnFailure) {
                    recordMissingExtracts(testCase, result, missingFrom);
                } else {
                    stopReason = stopReason(result.testName());
                }
            } else {
                skipped++;
            }
        }
        return new Outcome(passed, failed, skipped, List.copyOf(results));
    }

    /** Sends the case, re-sending a failure up to {@code retries} times. */
    private static RunTestResult attempt(StudioTestCase testCase, Actions actions, Options options) {
        RunTestResult result = actions.execute(testCase);
        int attempts = 1;
        while (!result.passed() && !result.skipped() && attempts <= options.retries()) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (options.delayMs() > 0) {
                actions.pause(options.delayMs());
            }
            result = actions.execute(testCase);
            attempts++;
        }
        if (attempts == 1) {
            return result;
        }
        List<AssertionCheck> checks = new ArrayList<>(result.assertions() == null ? List.of() : result.assertions());
        checks.add(new AssertionCheck("attempts", null, "up to " + (options.retries() + 1),
                String.valueOf(attempts), result.passed()));
        return result.withAssertions(List.copyOf(checks));
    }

    private static void recordMissingExtracts(StudioTestCase testCase, RunTestResult result,
                                              Map<String, String> missingFrom) {
        Map<String, String> extracted = result.extracted() == null ? Map.of() : result.extracted();
        String producer = displayName(result.testName());
        for (String name : CaseVariables.producedNames(testCase.getExtracts())) {
            String plain = name.startsWith(CaseVariables.EXTRACT_PREFIX)
                    ? name.substring(CaseVariables.EXTRACT_PREFIX.length()) : name;
            if (!extracted.containsKey(plain)) {
                missingFrom.putIfAbsent(name, producer);
            }
        }
    }

    private static String failedDependency(StudioTestCase testCase, Map<String, String> missingFrom, Actions actions) {
        if (missingFrom.isEmpty()) {
            return null;
        }
        for (String name : CaseVariables.placeholderNames(testCase)) {
            String producer = missingFrom.get(name);
            if (producer != null && !actions.defined(name)) {
                return dependencyReason(name, producer);
            }
        }
        return null;
    }

    static String dependencyReason(String variable, String producer) {
        return "Skipped because {{" + variable + "}} comes from '" + producer + "', which failed";
    }

    static String stopReason(String testName) {
        return "Stopped because '" + displayName(testName) + "' failed";
    }

    private static String displayName(String testName) {
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(testName), "Untitled case");
    }
}
