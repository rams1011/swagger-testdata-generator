package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * JSONPath reads (Jayway) and assertion operators shared by Try, Run, Run plan and extracts.
 * Keep in step with {@code api-automation} {@code validation/JsonPathEvaluator}.
 *
 * <p>Assertions are stored as {@code path=expected} pairs joined by {@code |}. The first {@code =} outside
 * brackets, parentheses and quotes ends the path, so filters such as {@code $.items[?(@.id==1)].name} work.
 * Expected values starting with {@code op:} select an operator; anything else is an equality check.
 */
final class JsonPathEvaluator {

    private static final Gson GSON = new Gson();
    private static final Configuration JAYWAY = Configuration.builder()
            .jsonProvider(new GsonJsonProvider())
            .mappingProvider(new GsonMappingProvider())
            .build();

    /** Result of one JSONPath assertion: what was found (as shown to the user) and whether it passed. */
    record Check(String actual, boolean passed) {
    }

    private JsonPathEvaluator() {
    }

    static Map<String, String> parseAssertions(String serialized) {
        Map<String, String> result = new LinkedHashMap<>();
        if (StringUtils.isBlank(serialized) || "NA".equalsIgnoreCase(serialized.trim())) {
            return result;
        }
        for (String pair : splitAssertions(serialized)) {
            int separator = pathSeparator(pair);
            if (separator < 0) {
                String path = pair.trim();
                if (!path.isEmpty()) {
                    result.put(path, "");
                }
                continue;
            }
            String path = pair.substring(0, separator).trim();
            String value = pair.substring(separator + 1);
            if ("{}".equals(value)) {
                value = "";
            }
            if (!path.isEmpty()) {
                result.put(path, value);
            }
        }
        return result;
    }

    /**
     * Reads {@code path} from {@code root} and applies {@code expected}; {@code root == null} means the body was not JSON.
     * When the path can match many values (wildcard, deep scan, filter, slice) the matches form a list: list checks
     * (exists, empty, contains, length, type) look at the list, and value checks pass only when every match passes.
     */
    static Check check(JsonElement root, String path, String expected) {
        if (root == null) {
            return new Check("(not JSON)", false);
        }
        String want = StringUtils.defaultString(expected);
        Read read;
        try {
            read = readOrThrow(root, path);
        } catch (InvalidPathException exception) {
            return new Check("(invalid JSONPath: " + StringUtils.defaultString(exception.getMessage()) + ")", false);
        }
        JsonElement value = read == null ? null : read.value();
        if ("op:notexists".equals(operatorName(want))) {
            boolean absent = value == null || value.isJsonNull();
            return new Check(absent ? "(missing)" : formatValue(value), absent);
        }
        if (value == null) {
            return new Check("(missing)", false);
        }
        if (read.definite() || !value.isJsonArray() || appliesToList(want)) {
            return new Check(formatValue(value), matches(want, value));
        }
        boolean passed = (!want.startsWith("op:") && equalsValue(want, value)) || everyMatch(want, value);
        return new Check(formatValue(value), passed);
    }

    private static boolean appliesToList(String want) {
        return switch (operatorName(want)) {
            case "op:exists", "op:nonempty", "op:empty", "op:contains", "op:notcontains", "op:length", "op:type" -> true;
            default -> false;
        };
    }

    private static boolean everyMatch(String want, JsonElement matches) {
        for (JsonElement item : matches.getAsJsonArray()) {
            if (!matches(want, item)) {
                return false;
            }
        }
        return true;
    }

    /** Extract value: a single match of a many-match path (such as a filter) is unwrapped; several stay a JSON list. */
    static String readText(JsonElement root, String jsonPath) {
        Read read;
        try {
            read = readOrThrow(root, jsonPath);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Invalid JSONPath " + jsonPath + ": " + exception.getMessage());
        }
        if (read == null) {
            return null;
        }
        JsonElement value = read.value();
        if (!read.definite() && value.isJsonArray() && value.getAsJsonArray().size() == 1) {
            value = value.getAsJsonArray().get(0);
        }
        return formatValue(value);
    }

    static boolean matches(String expected, JsonElement actual) {
        String want = StringUtils.defaultString(expected);
        if (want.startsWith("op:")) {
            return operatorMatches(want, actual);
        }
        return equalsValue(want, actual);
    }

    static String describe(String expected) {
        String want = StringUtils.defaultString(expected);
        if (!want.startsWith("op:")) {
            return want;
        }
        String arg = operatorArgument(want);
        return switch (operatorName(want)) {
            case "op:exists" -> "exists";
            case "op:notexists" -> "does not exist";
            case "op:nonempty" -> "non-empty";
            case "op:empty" -> "empty";
            case "op:matches" -> "matches /" + arg + "/";
            case "op:range" -> describeRange(arg);
            case "op:ne" -> "not " + arg;
            case "op:contains" -> "contains " + arg;
            case "op:notcontains" -> "does not contain " + arg;
            case "op:gt" -> "> " + arg;
            case "op:gte" -> ">= " + arg;
            case "op:lt" -> "< " + arg;
            case "op:lte" -> "<= " + arg;
            case "op:type" -> "type " + arg;
            case "op:length" -> "length " + (arg.contains("..") ? describeRange(arg) : arg);
            default -> want;
        };
    }

    /**
     * Splits {@code a=1|b=2}. {@code \|} is a literal bar. Inside the path part, a bar within brackets,
     * parentheses or quotes (for example a filter {@code ||}) does not split.
     */
    private static List<String> splitAssertions(String serialized) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        PathScanner scanner = new PathScanner();
        for (int i = 0; i < serialized.length(); i++) {
            char ch = serialized.charAt(i);
            if (ch == '\\' && i + 1 < serialized.length() && serialized.charAt(i + 1) == '|') {
                current.append('|');
                i++;
                continue;
            }
            if (scanner.inPath) {
                if (ch == '|' && scanner.topLevel()) {
                    parts.add(current.toString());
                    current.setLength(0);
                    scanner = new PathScanner();
                    continue;
                }
                scanner.accept(ch);
                current.append(ch);
                continue;
            }
            if (ch == '|') {
                parts.add(current.toString());
                current.setLength(0);
                scanner = new PathScanner();
                continue;
            }
            current.append(ch);
        }
        parts.add(current.toString());
        if (parts.size() > 1 && parts.get(parts.size() - 1).isEmpty()) {
            parts.remove(parts.size() - 1);
        }
        return parts;
    }

    /** Index of the {@code =} that ends the path, ignoring any inside brackets, parentheses or quotes. */
    static int pathSeparator(String pair) {
        PathScanner scanner = new PathScanner();
        for (int i = 0; i < pair.length(); i++) {
            char ch = pair.charAt(i);
            if (ch == '=' && scanner.topLevel()) {
                return i;
            }
            scanner.accept(ch);
        }
        return -1;
    }

    private static final class PathScanner {
        private boolean inPath = true;
        private int depth;
        private char quote;
        private boolean escaped;

        boolean topLevel() {
            return depth == 0 && quote == 0;
        }

        void accept(char ch) {
            if (escaped) {
                escaped = false;
                return;
            }
            if (quote != 0) {
                if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
                return;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
            } else if (ch == '[' || ch == '(') {
                depth++;
            } else if ((ch == ']' || ch == ')') && depth > 0) {
                depth--;
            } else if (ch == '=' && depth == 0) {
                inPath = false;
            }
        }
    }

    private static String operatorName(String want) {
        int colon = want.indexOf(':', 3);
        String name = colon < 0 ? want : want.substring(0, colon);
        return name.toLowerCase(Locale.ROOT);
    }

    private static String operatorArgument(String want) {
        int colon = want.indexOf(':', 3);
        return colon < 0 ? "" : want.substring(colon + 1);
    }

    private static boolean operatorMatches(String want, JsonElement actual) {
        String arg = operatorArgument(want);
        return switch (operatorName(want)) {
            case "op:exists" -> actual != null && !actual.isJsonNull();
            case "op:notexists" -> actual == null || actual.isJsonNull();
            case "op:nonempty" -> nonEmpty(actual);
            case "op:empty" -> actual != null && !nonEmpty(actual)
                    && (actual.isJsonNull() || !actual.isJsonPrimitive() || actual.getAsJsonPrimitive().isString());
            case "op:matches" -> patternMatches(arg, actual);
            case "op:range" -> inRange(arg, actual);
            case "op:ne" -> actual != null && !equalsValue(arg, actual);
            case "op:contains" -> contains(actual, arg);
            case "op:notcontains" -> actual != null && !actual.isJsonNull() && !contains(actual, arg);
            case "op:gt" -> compare(actual, arg, 1, false);
            case "op:gte" -> compare(actual, arg, 1, true);
            case "op:lt" -> compare(actual, arg, -1, false);
            case "op:lte" -> compare(actual, arg, -1, true);
            case "op:type" -> typeMatches(arg, actual);
            case "op:length" -> lengthMatches(arg, actual);
            default -> equalsValue(want, actual);
        };
    }

    private static boolean nonEmpty(JsonElement actual) {
        if (actual == null || actual.isJsonNull()) {
            return false;
        }
        if (actual.isJsonArray()) {
            return !actual.getAsJsonArray().isEmpty();
        }
        if (actual.isJsonObject()) {
            return !actual.getAsJsonObject().isEmpty();
        }
        if (actual.isJsonPrimitive() && actual.getAsJsonPrimitive().isString()) {
            return !actual.getAsString().trim().isEmpty();
        }
        return actual.isJsonPrimitive();
    }

    private static boolean patternMatches(String pattern, JsonElement actual) {
        if (actual == null || actual.isJsonNull() || pattern.isEmpty()) {
            return false;
        }
        try {
            return Pattern.compile(pattern).matcher(formatValue(actual)).matches();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    private static boolean inRange(String spec, JsonElement actual) {
        if (actual == null || !actual.isJsonPrimitive() || !actual.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        return numberInRange(spec, toBigDecimal(actual.getAsJsonPrimitive().getAsNumber()));
    }

    private static boolean numberInRange(String spec, BigDecimal value) {
        int dots = spec.indexOf("..");
        if (dots < 0) {
            return false;
        }
        String low = spec.substring(0, dots).trim();
        String high = spec.substring(dots + 2).trim();
        if (low.isEmpty() && high.isEmpty()) {
            return false;
        }
        if (!low.isEmpty() && (!isNumeric(low) || value.compareTo(new BigDecimal(low)) < 0)) {
            return false;
        }
        return high.isEmpty() || (isNumeric(high) && value.compareTo(new BigDecimal(high)) <= 0);
    }

    /** Strings contain substrings, arrays contain an equal element, objects contain a key. */
    private static boolean contains(JsonElement actual, String needle) {
        if (actual == null || actual.isJsonNull()) {
            return false;
        }
        if (actual.isJsonArray()) {
            for (JsonElement item : actual.getAsJsonArray()) {
                if (equalsValue(needle, item)) {
                    return true;
                }
            }
            return false;
        }
        if (actual.isJsonObject()) {
            return actual.getAsJsonObject().has(needle);
        }
        return formatValue(actual).contains(needle);
    }

    /** Numbers, or strings holding a number (many APIs send amounts as strings). */
    private static boolean compare(JsonElement actual, String bound, int direction, boolean inclusive) {
        BigDecimal value = numericValue(actual);
        if (value == null || !isNumeric(bound)) {
            return false;
        }
        int cmp = value.compareTo(new BigDecimal(bound.trim()));
        return cmp == direction || (inclusive && cmp == 0);
    }

    private static BigDecimal numericValue(JsonElement actual) {
        if (actual == null || !actual.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = actual.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return toBigDecimal(primitive.getAsNumber());
        }
        if (primitive.isString() && isNumeric(primitive.getAsString())) {
            return new BigDecimal(primitive.getAsString().trim());
        }
        return null;
    }

    private static boolean typeMatches(String type, JsonElement actual) {
        String want = StringUtils.trimToEmpty(type).toLowerCase(Locale.ROOT);
        if (actual == null) {
            return false;
        }
        return switch (want) {
            case "null" -> actual.isJsonNull();
            case "array" -> actual.isJsonArray();
            case "object" -> actual.isJsonObject();
            case "string" -> actual.isJsonPrimitive() && actual.getAsJsonPrimitive().isString();
            case "boolean" -> actual.isJsonPrimitive() && actual.getAsJsonPrimitive().isBoolean();
            case "number" -> actual.isJsonPrimitive() && actual.getAsJsonPrimitive().isNumber();
            case "integer" -> actual.isJsonPrimitive() && actual.getAsJsonPrimitive().isNumber()
                    && isWholeNumber(toBigDecimal(actual.getAsJsonPrimitive().getAsNumber()));
            default -> false;
        };
    }

    private static boolean isWholeNumber(BigDecimal value) {
        return value.signum() == 0 || value.scale() <= 0 || value.stripTrailingZeros().scale() <= 0;
    }

    /** {@code N} for an exact length, or {@code min..max} with either end optional. */
    private static boolean lengthMatches(String spec, JsonElement actual) {
        Integer length = lengthOf(actual);
        if (length == null) {
            return false;
        }
        String text = StringUtils.trimToEmpty(spec);
        if (text.contains("..")) {
            return numberInRange(text, BigDecimal.valueOf(length));
        }
        return isNumeric(text) && new BigDecimal(text).compareTo(BigDecimal.valueOf(length)) == 0;
    }

    private static Integer lengthOf(JsonElement actual) {
        if (actual == null || actual.isJsonNull()) {
            return null;
        }
        if (actual.isJsonArray()) {
            return actual.getAsJsonArray().size();
        }
        if (actual.isJsonObject()) {
            return actual.getAsJsonObject().size();
        }
        if (actual.getAsJsonPrimitive().isString()) {
            return actual.getAsString().length();
        }
        return null;
    }

    private static String describeRange(String spec) {
        int dots = spec.indexOf("..");
        if (dots < 0) {
            return spec;
        }
        String low = spec.substring(0, dots).trim();
        String high = spec.substring(dots + 2).trim();
        if (!low.isEmpty() && !high.isEmpty()) {
            return low + ".." + high;
        }
        if (!low.isEmpty()) {
            return ">= " + low;
        }
        if (!high.isEmpty()) {
            return "<= " + high;
        }
        return spec;
    }

    private static boolean equalsValue(String want, JsonElement actual) {
        if (actual == null || actual.isJsonNull()) {
            return want.isEmpty() || "null".equalsIgnoreCase(want);
        }
        if (actual.isJsonPrimitive()) {
            JsonPrimitive primitive = actual.getAsJsonPrimitive();
            if (primitive.isNumber() && isNumeric(want)) {
                return numbersEqual(want, primitive.getAsNumber());
            }
        }
        if (want.equals(formatValue(actual))) {
            return true;
        }
        if (actual.isJsonArray() || actual.isJsonObject()) {
            JsonElement parsed = parseJson(want);
            return parsed != null && parsed.equals(actual);
        }
        return false;
    }

    static String formatValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return formatNumber(primitive.getAsNumber());
            }
            return primitive.getAsString();
        }
        return element.toString();
    }

    static boolean isNumeric(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        try {
            new BigDecimal(value.trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static boolean numbersEqual(String expected, Number actual) {
        try {
            return toBigDecimal(actual).compareTo(new BigDecimal(expected.trim())) == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String formatNumber(Number number) {
        return toBigDecimal(number).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal toBigDecimal(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException ignored) {
            return BigDecimal.valueOf(number.doubleValue());
        }
    }

    static JsonElement parseJson(String json) {
        if (StringUtils.isBlank(json)) {
            return JsonNull.INSTANCE;
        }
        try {
            return JsonParser.parseString(json);
        } catch (JsonSyntaxException ignored) {
            return null;
        }
    }

    /** Value at {@code jsonPath}, or {@code null} when missing or the path is invalid. */
    static JsonElement read(JsonElement root, String jsonPath) {
        try {
            Read read = readOrThrow(root, jsonPath);
            return read == null ? null : read.value();
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private record Read(JsonElement value, boolean definite) {
    }

    /**
     * Full Jayway syntax: wildcards, deep scan, filters, slices, functions such as {@code length()}.
     * A path that can match many nodes returns a JSON array of matches, or {@code null} when nothing matched.
     */
    private static Read readOrThrow(JsonElement root, String jsonPath) {
        if (root == null || root.isJsonNull() || StringUtils.isBlank(jsonPath)) {
            return null;
        }
        String path = normalizePath(jsonPath);
        if ("$".equals(path)) {
            return new Read(root, true);
        }
        JsonPath compiled = JsonPath.compile(path);
        Object value;
        try {
            value = compiled.read(root, JAYWAY);
        } catch (PathNotFoundException exception) {
            return null;
        }
        JsonElement element = value instanceof JsonElement json ? json : GSON.toJsonTree(value);
        if (!compiled.isDefinite() && element.isJsonArray() && element.getAsJsonArray().isEmpty()) {
            return null;
        }
        return new Read(element, compiled.isDefinite());
    }

    private static String normalizePath(String jsonPath) {
        String path = jsonPath.trim();
        if (path.startsWith("$")) {
            return path;
        }
        return path.startsWith("[") ? "$" + path : "$." + path;
    }
}
