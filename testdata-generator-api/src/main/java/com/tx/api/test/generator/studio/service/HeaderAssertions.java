package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.dto.AssertionCheck;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Header and Set-Cookie checks stored beside JSON path assertions as {@code header:Name=value}.
 */
final class HeaderAssertions {

    private HeaderAssertions() {
    }

    static boolean isTransport(String path) {
        if (path == null) {
            return false;
        }
        String trimmed = path.trim();
        return trimmed.regionMatches(true, 0, "header:", 0, "header:".length())
                || trimmed.regionMatches(true, 0, "cookie:", 0, "cookie:".length());
    }

    static List<AssertionCheck> checks(String serialized, Map<String, List<String>> headers) {
        List<AssertionCheck> checks = new ArrayList<>();
        JsonPathEvaluator.parseAssertions(serialized).forEach((path, expected) -> {
            if (isTransport(path)) {
                checks.add(one(path, expected, headers));
            }
        });
        return checks;
    }

    static boolean valuesMatch(String expected, String actual) {
        String want = StringUtils.defaultString(expected).trim();
        String got = StringUtils.defaultString(actual).trim();
        if (want.equals(got)) {
            return true;
        }
        int semi = got.indexOf(';');
        String mediaType = semi < 0 ? got : got.substring(0, semi).trim();
        return !want.contains(";") && want.equalsIgnoreCase(mediaType);
    }

    private static AssertionCheck one(String path, String expected, Map<String, List<String>> headers) {
        boolean cookie = path.trim().regionMatches(true, 0, "cookie:", 0, "cookie:".length());
        String name = path.trim().substring(path.trim().indexOf(':') + 1).trim();
        String actual = cookie ? cookieValue(headers, name) : firstHeader(headers, name);
        String want = StringUtils.defaultString(expected);
        boolean match;
        String shown;
        if (actual == null) {
            shown = "(missing)";
            match = false;
        } else if (want.isBlank()) {
            shown = actual;
            match = !actual.isBlank();
        } else {
            shown = actual;
            match = valuesMatch(want, actual);
        }
        return new AssertionCheck(cookie ? "cookie" : "header", name, want, shown, match);
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null || StringUtils.isBlank(name)) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static String cookieValue(Map<String, List<String>> headers, String cookieName) {
        if (headers == null || StringUtils.isBlank(cookieName)) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie") || entry.getValue() == null) {
                continue;
            }
            for (String header : entry.getValue()) {
                String value = parseCookie(header, cookieName);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String parseCookie(String setCookie, String cookieName) {
        if (StringUtils.isBlank(setCookie)) {
            return null;
        }
        String first = setCookie.split(";", 2)[0].trim();
        int separator = first.indexOf('=');
        if (separator < 0 || !cookieName.equals(first.substring(0, separator).trim())) {
            return null;
        }
        return first.substring(separator + 1).trim();
    }
}
