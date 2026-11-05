package com.tx.api.test.generator.studio.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Compares an expected JSON body with the actual one and names the first difference.
 * <ul>
 *   <li>{@code strict} (default): same fields, same list items in the same order.</li>
 *   <li>{@code ignoreOrder}: same fields and list items, but lists may be in any order.</li>
 *   <li>{@code lenient}: the expected JSON is a subset; the actual body may have extra fields and extra list items,
 *       and lists may be in any order.</li>
 * </ul>
 * Numbers compare by value ({@code 48} equals {@code 48.0}); a number never equals a string ({@code 48} vs {@code "48"}).
 */
public final class JsonBodyMatcher {

    public static final String STRICT = "strict";
    public static final String IGNORE_ORDER = "ignoreOrder";
    public static final String LENIENT = "lenient";

    private static final Pattern SIMPLE_KEY = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final int PREVIEW = 120;

    public enum Mode {
        STRICT(JsonBodyMatcher.STRICT, false, false),
        IGNORE_ORDER(JsonBodyMatcher.IGNORE_ORDER, true, false),
        LENIENT(JsonBodyMatcher.LENIENT, true, true);

        private final String value;
        private final boolean anyOrder;
        private final boolean subset;

        Mode(String value, boolean anyOrder, boolean subset) {
            this.value = value;
            this.anyOrder = anyOrder;
            this.subset = subset;
        }

        public String value() {
            return value;
        }

        public String label() {
            return switch (this) {
                case STRICT -> "strict";
                case IGNORE_ORDER -> "ignore list order";
                case LENIENT -> "lenient";
            };
        }
    }

    private JsonBodyMatcher() {
    }

    /** {@code strict}, {@code ignoreOrder} ({@code ignore_order}, {@code ignore-order}) or {@code lenient}; else strict. */
    public static Mode mode(String value) {
        String key = StringUtils.trimToEmpty(value).replace("_", "").replace("-", "").replace(" ", "")
                .toLowerCase(Locale.ROOT);
        return switch (key) {
            case "ignoreorder", "anyorder", "unordered" -> Mode.IGNORE_ORDER;
            case "lenient", "subset", "contains" -> Mode.LENIENT;
            default -> Mode.STRICT;
        };
    }

    /** Stored form: {@code null} for strict (the default), otherwise the mode's value. */
    public static String normalize(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        Mode mode = mode(value);
        return mode == Mode.STRICT ? null : mode.value();
    }

    /** First difference such as {@code $.items[0].roleId: expected 48 but was "48"}, or {@code null} when they match. */
    public static String difference(JsonElement expected, JsonElement actual, Mode mode) {
        return diff("$", expected, actual, mode == null ? Mode.STRICT : mode);
    }

    public static boolean matches(JsonElement expected, JsonElement actual, Mode mode) {
        return difference(expected, actual, mode) == null;
    }

    private static String diff(String path, JsonElement expected, JsonElement actual, Mode mode) {
        if (expected == null || expected.isJsonNull()) {
            return actual == null || actual.isJsonNull() ? null : path + ": expected null but was " + preview(actual);
        }
        if (actual == null) {
            return path + ": missing";
        }
        if (expected.isJsonObject()) {
            if (!actual.isJsonObject()) {
                return path + ": expected an object but was " + preview(actual);
            }
            return objectDiff(path, expected.getAsJsonObject(), actual.getAsJsonObject(), mode);
        }
        if (expected.isJsonArray()) {
            if (!actual.isJsonArray()) {
                return path + ": expected a list but was " + preview(actual);
            }
            return arrayDiff(path, expected.getAsJsonArray(), actual.getAsJsonArray(), mode);
        }
        return primitivesEqual(expected.getAsJsonPrimitive(), actual) ? null
                : path + ": expected " + preview(expected) + " but was " + preview(actual);
    }

    private static String objectDiff(String path, JsonObject expected, JsonObject actual, Mode mode) {
        for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
            String child = child(path, entry.getKey());
            if (!actual.has(entry.getKey())) {
                return child + ": missing";
            }
            String difference = diff(child, entry.getValue(), actual.get(entry.getKey()), mode);
            if (difference != null) {
                return difference;
            }
        }
        if (!mode.subset) {
            for (String key : actual.keySet()) {
                if (!expected.has(key)) {
                    return child(path, key) + ": unexpected field (use lenient to allow extra fields)";
                }
            }
        }
        return null;
    }

    private static String arrayDiff(String path, JsonArray expected, JsonArray actual, Mode mode) {
        if (mode.subset ? actual.size() < expected.size() : actual.size() != expected.size()) {
            return path + ": expected " + (mode.subset ? "at least " : "") + expected.size()
                    + (expected.size() == 1 ? " item" : " items") + " but was " + actual.size();
        }
        if (!mode.anyOrder) {
            for (int i = 0; i < expected.size(); i++) {
                String difference = diff(path + "[" + i + "]", expected.get(i), actual.get(i), mode);
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }
        int unmatched = unmatchedIndex(expected, actual, mode);
        if (unmatched < 0) {
            return null;
        }
        if (actual.size() == 1) {
            return diff(path + "[0]", expected.get(unmatched), actual.get(0), mode);
        }
        return path + ": no item matches expected[" + unmatched + "] " + preview(expected.get(unmatched));
    }

    /**
     * Pairs each expected item with a different actual item (bipartite matching, so {@code [1, 1]} needs two 1s).
     * Returns the first expected index left without a partner, or -1 when all are paired.
     */
    private static int unmatchedIndex(JsonArray expected, JsonArray actual, Mode mode) {
        int n = expected.size();
        int m = actual.size();
        Boolean[][] fits = new Boolean[n][m];
        int[] owner = new int[m];
        Arrays.fill(owner, -1);
        for (int i = 0; i < n; i++) {
            if (!assign(i, expected, actual, mode, fits, owner, new boolean[m])) {
                return i;
            }
        }
        return -1;
    }

    private static boolean assign(int i, JsonArray expected, JsonArray actual, Mode mode, Boolean[][] fits,
                                  int[] owner, boolean[] seen) {
        for (int j = 0; j < actual.size(); j++) {
            if (seen[j]) {
                continue;
            }
            if (fits[i][j] == null) {
                fits[i][j] = diff("$", expected.get(i), actual.get(j), mode) == null;
            }
            if (!fits[i][j]) {
                continue;
            }
            seen[j] = true;
            if (owner[j] < 0 || assign(owner[j], expected, actual, mode, fits, owner, seen)) {
                owner[j] = i;
                return true;
            }
        }
        return false;
    }

    private static boolean primitivesEqual(JsonPrimitive expected, JsonElement actual) {
        if (!actual.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive other = actual.getAsJsonPrimitive();
        if (expected.isNumber() && other.isNumber()) {
            try {
                return new BigDecimal(expected.getAsString()).compareTo(new BigDecimal(other.getAsString())) == 0;
            } catch (NumberFormatException ignored) {
                return expected.equals(other);
            }
        }
        if (expected.isNumber() != other.isNumber() || expected.isBoolean() != other.isBoolean()) {
            return false;
        }
        return expected.getAsString().equals(other.getAsString());
    }

    private static String child(String path, String key) {
        return SIMPLE_KEY.matcher(key).matches() ? path + "." + key : path + "['" + key.replace("'", "\\'") + "']";
    }

    private static String preview(JsonElement value) {
        if (value == null) {
            return "(missing)";
        }
        String text = value.toString();
        return text.length() <= PREVIEW ? text : text.substring(0, PREVIEW) + "…";
    }
}
