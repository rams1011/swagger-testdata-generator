package com.tx.api.test.generator.studio.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JsonPathEvaluator {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*");

    private JsonPathEvaluator() {
    }

    static Map<String, String> parseAssertions(String serialized) {
        Map<String, String> result = new LinkedHashMap<>();
        if (StringUtils.isBlank(serialized) || "NA".equalsIgnoreCase(serialized.trim())) {
            return result;
        }
        for (String pair : serialized.split("\\|")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                result.put(trimmed, "");
                continue;
            }
            String path = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            if ("{}".equals(value)) {
                value = "";
            }
            if (!path.isEmpty()) {
                result.put(path, value);
            }
        }
        return result;
    }

    static String formatValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            return primitive.getAsString();
        }
        return element.toString();
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
        if ("$".equals(path)) {
            return root;
        }
        if (!path.startsWith("$")) {
            return null;
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
