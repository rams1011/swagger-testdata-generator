package com.tx.api.test.generator.studio.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class JsonPathEvaluator {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*");

    private JsonPathEvaluator() {
    }

    static Map<String, String> parseAssertions(String serialized) {
        Map<String, String> result = new LinkedHashMap<>();
        if (StringUtils.isBlank(serialized) || "NA".equalsIgnoreCase(serialized.trim())) {
            return result;
        }
        for (String pair : splitAssertions(serialized)) {
            int separator = pair.indexOf('=');
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

    static boolean matches(String expected, JsonElement actual) {
        String want = StringUtils.defaultString(expected);
        if (want.startsWith("op:")) {
            return operatorMatches(want, actual);
        }
        return equalsValue(want, actual);
    }

    static String describe(String expected) {
        String want = StringUtils.defaultString(expected);
        if ("op:exists".equals(want)) {
            return "exists";
        }
        if ("op:nonempty".equals(want)) {
            return "non-empty";
        }
        if (want.startsWith("op:matches:")) {
            return "matches /" + want.substring("op:matches:".length()) + "/";
        }
        if (want.startsWith("op:range:")) {
            return describeRange(want.substring("op:range:".length()));
        }
        return want;
    }

    private static List<String> splitAssertions(String serialized) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < serialized.length(); i++) {
            char ch = serialized.charAt(i);
            if (ch == '\\' && i + 1 < serialized.length() && serialized.charAt(i + 1) == '|') {
                current.append('|');
                i++;
                continue;
            }
            if (ch == '|') {
                parts.add(current.toString());
                current.setLength(0);
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

    private static boolean operatorMatches(String want, JsonElement actual) {
        if ("op:exists".equals(want)) {
            return actual != null && !actual.isJsonNull();
        }
        if ("op:nonempty".equals(want)) {
            return nonEmpty(actual);
        }
        if (want.startsWith("op:matches:")) {
            return patternMatches(want.substring("op:matches:".length()), actual);
        }
        if (want.startsWith("op:range:")) {
            return inRange(want.substring("op:range:".length()), actual);
        }
        return equalsValue(want, actual);
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
        int dots = spec.indexOf("..");
        if (dots < 0) {
            return false;
        }
        String low = spec.substring(0, dots).trim();
        String high = spec.substring(dots + 2).trim();
        if (low.isEmpty() && high.isEmpty()) {
            return false;
        }
        BigDecimal value = toBigDecimal(actual.getAsJsonPrimitive().getAsNumber());
        if (!low.isEmpty() && (!isNumeric(low) || value.compareTo(new BigDecimal(low)) < 0)) {
            return false;
        }
        return high.isEmpty() || (isNumeric(high) && value.compareTo(new BigDecimal(high)) <= 0);
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
        return want.equals(formatValue(actual));
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

    static JsonElement read(JsonElement root, String jsonPath) {
        if (root == null || root.isJsonNull() || StringUtils.isBlank(jsonPath)) {
            return null;
        }
        String path = jsonPath.trim();
        if (!path.startsWith("$")) {
            path = path.startsWith("[") ? "$" + path : "$." + path;
        }
        if ("$".equals(path)) {
            return root;
        }
        JsonElement current = root;
        int index = 1;
        while (index < path.length()) {
            if (current == null || current.isJsonNull()) {
                return null;
            }
            char ch = path.charAt(index);
            if (ch == '.') {
                index += 1;
                Matcher match = NAME.matcher(path.substring(index));
                if (!match.find() || match.start() != 0) {
                    return null;
                }
                String name = match.group();
                current = current.isJsonObject() ? current.getAsJsonObject().get(name) : null;
                index += name.length();
                continue;
            }
            if (ch == '[') {
                int close = path.indexOf(']', index);
                if (close < 0) {
                    return null;
                }
                String token = path.substring(index + 1, close);
                current = readIndex(current, token);
                index = close + 1;
                continue;
            }
            return null;
        }
        return current;
    }

    private static JsonElement readIndex(JsonElement current, String token) {
        if (token.length() >= 2
                && ((token.startsWith("'") && token.endsWith("'"))
                || (token.startsWith("\"") && token.endsWith("\"")))) {
            String name = token.substring(1, token.length() - 1).replace("\\'", "'");
            return current.isJsonObject() ? current.getAsJsonObject().get(name) : null;
        }
        if (token.matches("^\\d+$") && current.isJsonArray()) {
            JsonArray array = current.getAsJsonArray();
            int position = Integer.parseInt(token);
            if (position < 0 || position >= array.size()) {
                return null;
            }
            return array.get(position);
        }
        return null;
    }
}
