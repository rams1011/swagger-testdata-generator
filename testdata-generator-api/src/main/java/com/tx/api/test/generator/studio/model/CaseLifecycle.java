package com.tx.api.test.generator.studio.model;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * Authoring state for a saved case. Drafts stay in Studio and are omitted from export, Run plan, and JDBC CI.
 * A published case with {@code enabled=N} stays in Studio and is omitted from export. Run plan already skips it.
 */
public final class CaseLifecycle {

    public static final String DRAFT = "DRAFT";
    public static final String READY = "READY";

    private CaseLifecycle() {
    }

    public static String normalize(String value) {
        String trimmed = StringUtils.trimToNull(value);
        if (trimmed == null) {
            return READY;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (DRAFT.equals(upper) || READY.equals(upper)) {
            return upper;
        }
        throw new IllegalArgumentException("lifecycle must be DRAFT or READY");
    }

    /** Blank keeps {@code fallback}; a present value must be DRAFT or READY. */
    public static String normalizeOr(String value, String fallback) {
        if (StringUtils.isBlank(value)) {
            return normalize(fallback);
        }
        return normalize(value);
    }

    public static boolean isDraft(String lifecycle) {
        return DRAFT.equals(normalizeOr(lifecycle, READY));
    }

    public static boolean isDraft(StudioTestCase testCase) {
        return testCase != null && isDraft(testCase.getLifecycle());
    }

    /** Blank or anything other than N counts as enabled. */
    public static boolean isEnabled(StudioTestCase testCase) {
        if (testCase == null) {
            return false;
        }
        return !"N".equalsIgnoreCase(StringUtils.trimToEmpty(testCase.getEnabled()));
    }

    /** Published, enabled cases are eligible for collection and Excel export. */
    public static boolean isExportable(StudioTestCase testCase) {
        return testCase != null && !isDraft(testCase) && isEnabled(testCase);
    }
}
