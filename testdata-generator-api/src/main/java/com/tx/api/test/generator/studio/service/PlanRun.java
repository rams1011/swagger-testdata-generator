package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs plan cases in order and stops after the first failure so later cases are not sent.
 */
final class PlanRun {

    private PlanRun() {
    }

    interface Actions {
        boolean runnable(StudioTestCase testCase);

        RunTestResult execute(StudioTestCase testCase);

        /** {@code reason} is null for a case that was already out of the run (draft, disabled, or other environment). */
        RunTestResult skip(StudioTestCase testCase, String reason);
    }

    record Outcome(int passed, int failed, int skipped, List<RunTestResult> results) {
    }

    static Outcome execute(List<StudioTestCase> cases, Actions actions) {
        List<RunTestResult> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        String stopReason = null;
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
            RunTestResult result = actions.execute(testCase);
            results.add(result);
            if (result.passed()) {
                passed++;
            } else if (!result.skipped()) {
                failed++;
                stopReason = stopReason(result.testName());
            } else {
                skipped++;
            }
        }
        return new Outcome(passed, failed, skipped, List.copyOf(results));
    }

    static String stopReason(String testName) {
        String name = StringUtils.defaultIfBlank(StringUtils.trimToNull(testName), "Untitled case");
        return "Stopped because '" + name + "' failed";
    }
}
