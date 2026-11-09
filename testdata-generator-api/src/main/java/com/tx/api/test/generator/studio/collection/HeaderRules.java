package com.tx.api.test.generator.studio.collection;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What a request header may be: a token name, a value without line breaks or control characters, and not one of the
 * headers the HTTP client writes itself from the URL and body.
 */
public final class HeaderRules {

    private static final Pattern NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> SET_BY_CLIENT = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        SET_BY_CLIENT.addAll(Set.of("Connection", "Content-Length", "Expect", "Host", "Upgrade"));
    }

    private HeaderRules() {
    }

    /** Null when the header can be sent; otherwise why not, starting with "header '<name>'". */
    public static String problem(String name, String value) {
        String label = "header '" + name + "'";
        if (name == null || !NAME.matcher(name).matches()) {
            return label + " isn't a valid header name";
        }
        if (SET_BY_CLIENT.contains(name)) {
            return label + " can't be set by a test: the HTTP client sets it from the URL and body";
        }
        if (value != null) {
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if ((character < 0x20 && character != '\t') || character == 0x7F) {
                    return label + " has a line break or control character in its value";
                }
            }
        }
        return null;
    }

    /** The first problem among {@code headers}, or null. Blank names are left out, as everywhere else. */
    public static String firstProblem(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() == null || header.getKey().isBlank()) {
                continue;
            }
            String problem = problem(header.getKey().trim(), header.getValue());
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }
}
