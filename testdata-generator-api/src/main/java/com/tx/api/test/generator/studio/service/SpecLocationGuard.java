package com.tx.api.test.generator.studio.service;

/**
 * Spec import may fetch an http(s) URL. File paths and other schemes stay on the machine
 * that called the API, and the same host check used by Try and Run applies first.
 */
public final class SpecLocationGuard {

    private SpecLocationGuard() {
    }

    public static boolean isRemote(String location) {
        if (location == null) {
            return false;
        }
        String trimmed = location.trim();
        return trimmed.regionMatches(true, 0, "http://", 0, "http://".length())
                || trimmed.regionMatches(true, 0, "https://", 0, "https://".length());
    }

    public static void check(String location, OutboundUrlPolicy policy) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Spec location is required.");
        }
        if (!isRemote(location)) {
            throw new IllegalArgumentException("Spec location must be an http or https URL.");
        }
        if (policy == null) {
            throw new IllegalArgumentException("Spec location is not allowed.");
        }
        policy.check(location.trim());
    }

    /** Value safe to store and return. Server file paths are omitted. */
    public static String publicLocation(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String trimmed = location.trim();
        if (isRemote(trimmed)) {
            return trimmed;
        }
        if (isInlineMarker(trimmed) || isSafeFileName(trimmed)) {
            return trimmed;
        }
        return null;
    }

    static boolean isInlineMarker(String location) {
        return "inline:json".equals(location) || "inline:yaml".equals(location);
    }

    static boolean isSafeFileName(String location) {
        if (location.contains("/") || location.contains("\\") || location.contains("..")) {
            return false;
        }
        return !location.isBlank();
    }
}
