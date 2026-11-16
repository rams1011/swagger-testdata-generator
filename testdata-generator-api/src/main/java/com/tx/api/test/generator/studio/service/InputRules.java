package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.collection.HeaderRules;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.apache.commons.lang3.StringUtils;

/**
 * Rules every way into Studio applies the same: saving in the editor, loading a definition, adding an API, and
 * importing a collection or environment file. Breaking one gives a 400 with a message instead of a database error or a
 * value a run can't use.
 */
public final class InputRules {

    /** Names are VARCHAR(255) and groups VARCHAR(128). */
    public static final int MAX_NAME_LENGTH = 255;
    public static final int MAX_GROUP_LENGTH = 128;
    /** The same cap as a run's request timeout (testdata.run.max-request-timeout-ms default). */
    public static final long MAX_RESPONSE_TIME_MS = 300_000L;

    private InputRules() {
    }

    /** {@code value} unchanged when it fits; otherwise a message naming {@code label}. */
    public static String limitedText(String label, String value, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(label + " can be at most " + max + " characters (it has "
                    + value.length() + ")");
        }
        return value;
    }

    /**
     * A base URL is an http or https address with a host (and a port from 1 to 65535); the UI checks the same. A
     * {{variable}} in it is left to run time. {@code label} starts the message ("Base URL", "API base URL").
     */
    public static void requireHttpBaseUrl(String label, String baseUrl) {
        String text = StringUtils.trimToEmpty(baseUrl);
        if (text.contains("{{")) {
            return;
        }
        if (!text.regionMatches(true, 0, "http://", 0, 7) && !text.regionMatches(true, 0, "https://", 0, 8)) {
            throw new IllegalArgumentException(label + " must start with http:// or https://");
        }
        if (text.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(label + " can't contain spaces");
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(text);
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException(label + " is not a valid URL");
        }
        if (StringUtils.isBlank(uri.getHost())) {
            throw new IllegalArgumentException(label + " needs a host name, such as https://api.example.com");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException(label + " port must be from 1 to 65535");
        }
    }

    public static void requireMaxResponseTime(Long maxResponseTimeMs) {
        if (maxResponseTimeMs != null && (maxResponseTimeMs < 1 || maxResponseTimeMs > MAX_RESPONSE_TIME_MS)) {
            throw new IllegalArgumentException("Max response time must be from 1 to " + MAX_RESPONSE_TIME_MS
                    + " milliseconds");
        }
    }

    public static void requireIgnoreFields(String ignoreFields) {
        for (String path : JsonBodyMatcher.ignorePaths(ignoreFields)) {
            String problem = JsonPathEvaluator.ignoreFieldProblem(path);
            if (problem != null) {
                throw new IllegalArgumentException(problem);
            }
        }
    }

    /**
     * The case rules a save enforces, for a case built another way (an imported collection). {@code label} names the
     * case in the message.
     */
    public static void requireStorableCase(StudioTestCase testCase, String label) {
        try {
            limitedText("Test name", testCase.getTestName(), MAX_NAME_LENGTH);
            limitedText("Group", testCase.getTestGroup(), MAX_GROUP_LENGTH);
            long max = testCase.getMaxResponseTimeMs();
            // 0 is how older files said "use the default"; it still runs as 3 seconds.
            if (max != 0) {
                requireMaxResponseTime(max);
            }
            requireIgnoreFields(testCase.getIgnoreFields());
            String headerProblem = HeaderRules.firstProblem(testCase.getHeaders());
            if (headerProblem != null) {
                throw new IllegalArgumentException(StringUtils.capitalize(headerProblem));
            }
        } catch (IllegalArgumentException problem) {
            throw new IllegalArgumentException(label + ": " + problem.getMessage());
        }
    }
}
