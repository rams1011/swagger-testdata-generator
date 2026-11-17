package com.tx.api.test.generator.studio.safety;

import java.net.URI;

/**
 * Matches an operation rule's path template ({@code /users/{id}}) against the path a request goes to. The path is
 * normalized first ({@code /a/../b} is {@code /b}) and the query is never part of it, so {@code /search?x=../admin}
 * matches {@code /search} and nothing else. A {@code {name}} stands for exactly one non-empty segment.
 */
public final class PathTemplates {

    private PathTemplates() {
    }

    public static boolean matches(String template, String url) {
        String path = path(url);
        if (template == null || path == null) {
            return false;
        }
        String[] wanted = trim(template).split("/", -1);
        String[] actual = trim(path).split("/", -1);
        if (wanted.length != actual.length) {
            return false;
        }
        for (int index = 0; index < wanted.length; index++) {
            String part = wanted[index];
            boolean variable = part.startsWith("{") && part.endsWith("}") && part.length() > 2;
            if (variable ? actual[index].isEmpty() : !part.equals(actual[index])) {
                return false;
            }
        }
        return true;
    }

    /** The normalized raw path of a URL, without its query; null when the URL can't be read. */
    static String path(String url) {
        if (url == null) {
            return null;
        }
        try {
            String raw = URI.create(url.trim()).normalize().getRawPath();
            return raw == null || raw.isEmpty() ? "/" : raw;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String trim(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
