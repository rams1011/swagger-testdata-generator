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

    /** Names are VARCHAR(255) and groups VARCHAR(128): the most an imported file may bring in. */
    public static final int MAX_NAME_LENGTH = 255;
    public static final int MAX_GROUP_LENGTH = 128;

    /*
     * What a person types. These names are labels shown in the sidebar, the workspace bar, dropdowns, chips and
     * exported file names, so they are kept to what reads well there. Test names stay at the storage limit: the case
     * generator builds them from the operation summary plus a description such as
     * "— missing required body field customer.address.postcode — 400".
     */
    public static final int MAX_COLLECTION_NAME = 100;
    public static final int MAX_LABEL = 40;
    public static final int MAX_PLAN_NAME = 60;
    public static final int MAX_ENVIRONMENT_NAME = 40;
    public static final int MAX_GROUP = 60;
    public static final int MAX_TEST_NAME = MAX_NAME_LENGTH;

    /**
     * The typed-name limits for the UI, sent in the runtime config as {@code name=number} pairs, so the UI's inputs use
     * these numbers instead of keeping their own copy.
     */
    public static String nameLimitsConfig() {
        return "collection=" + MAX_COLLECTION_NAME + ",label=" + MAX_LABEL + ",plan=" + MAX_PLAN_NAME
                + ",environment=" + MAX_ENVIRONMENT_NAME + ",group=" + MAX_GROUP + ",testName=" + MAX_TEST_NAME;
    }
    /** The same cap as a run's request timeout (testdata.run.max-request-timeout-ms default). */
    public static final long MAX_RESPONSE_TIME_MS = 300_000L;

    private InputRules() {
    }

    /**
     * Like {@link #limitedText}, but a value equal to {@code previous} is let through: a name stored before the limit
     * (or brought in by an import) doesn't stop saving other changes, only renaming it does.
     */
    public static String limitedName(String label, String value, int max, String previous) {
        if (value != null && previous != null && value.trim().equals(previous.trim())) {
            return value;
        }
        return limitedText(label, value, max);
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
        if (uri.getRawUserInfo() != null || StringUtils.substringBefore(
                StringUtils.defaultString(uri.getRawAuthority()), "/").contains("@")) {
            // Stored and exported as plain text; credentials belong in auth with a secret {{variable}}.
            throw new IllegalArgumentException(label + " can't contain a user name or password: use Basic auth "
                    + "with a secret {{variable}} instead");
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
            CaseRules.requireStorable(testCase);
        } catch (IllegalArgumentException problem) {
            throw new IllegalArgumentException(label + ": " + problem.getMessage());
        }
    }
}
