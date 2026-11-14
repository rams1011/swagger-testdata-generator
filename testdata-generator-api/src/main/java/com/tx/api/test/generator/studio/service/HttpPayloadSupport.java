package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HttpPayloadSupport {

    private static final Gson JSON = new Gson();

    private HttpPayloadSupport() {
    }

    static boolean isFormUrlEncoded(String contentType) {
        if (StringUtils.isBlank(contentType)) {
            return false;
        }
        String value = contentType.trim().toLowerCase(Locale.ROOT);
        return value.contains("x-www-form-urlencoded") || "form".equals(value) || "urlencoded".equals(value);
    }

    static String formUrlEncoded(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String text) {
            return StringUtils.isBlank(text) ? null : text;
        }
        List<String[]> pairs = new ArrayList<>();
        flatten("", body, pairs);
        if (pairs.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (String[] entry : pairs) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(encode(entry[0])).append('=').append(encode(entry[1]));
        }
        return builder.toString();
    }

    static String appendQuery(String url, Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return url;
        }
        StringBuilder builder = new StringBuilder(url);
        boolean first = !url.contains("?");
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (StringUtils.isBlank(entry.getKey())) {
                continue;
            }
            for (String raw : explode(entry.getValue())) {
                // An empty box is an unset optional parameter, not a request to send name=; "" sends name= on purpose.
                String value = sendable(raw);
                if (value == null) {
                    continue;
                }
                builder.append(first ? '?' : '&');
                first = false;
                builder.append(encode(entry.getKey())).append('=').append(encode(value));
            }
        }
        return builder.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * A path parameter value as one path segment: letters, digits and {@code - . _ ~} stay, a valid {@code %XX} that
     * is already there stays (no double encoding), and every other byte of its UTF-8 form becomes {@code %XX}, so
     * {@code a/b} is {@code a%2Fb} and {@code a b} is {@code a%20b}. api-automation encodes the same way.
     */
    static String encodePathValue(String value) {
        byte[] bytes = StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 8);
        for (int index = 0; index < bytes.length; index++) {
            int current = bytes[index] & 0xFF;
            if ((current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9') || current == '-' || current == '.' || current == '_'
                    || current == '~') {
                out.append((char) current);
            } else if (current == '%' && index + 2 < bytes.length && isHex(bytes[index + 1]) && isHex(bytes[index + 2])) {
                out.append('%').append((char) bytes[index + 1]).append((char) bytes[index + 2]);
                index += 2;
            } else {
                out.append('%').append(HEX[current >> 4]).append(HEX[current & 0x0F]);
            }
        }
        return out.toString();
    }

    private static boolean isHex(byte value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f') || (value >= 'A' && value <= 'F');
    }

    /** Typed as a value, {@code ""} means "send this empty on purpose" (an empty box isn't sent at all). */
    static final String EMPTY_ON_PURPOSE = "\"\"";

    /**
     * What to send for a query, header or cookie value: null to leave it out (blank), "" for {@code ""}, else the
     * value. api-automation follows the same rule.
     */
    static String sendable(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return EMPTY_ON_PURPOSE.equals(value.trim()) ? "" : value;
    }

    static String cookieHeader(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        cookies.forEach((name, value) -> {
            if (StringUtils.isBlank(name) || value == null) {
                return;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(name.trim()).append('=').append(value);
        });
        return builder.length() == 0 ? null : builder.toString();
    }

    static List<String> explode(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                JsonElement parsed = JsonParser.parseString(trimmed);
                if (parsed.isJsonArray()) {
                    JsonArray array = parsed.getAsJsonArray();
                    if (array.isEmpty()) {
                        return values;
                    }
                    for (JsonElement element : array) {
                        values.add(element.isJsonNull() ? "" : element.isJsonPrimitive()
                                ? element.getAsString()
                                : JSON.toJson(element));
                    }
                    return values;
                }
            } catch (RuntimeException ignored) {
                // treat as a single scalar
            }
        }
        values.add(raw);
        return values;
    }

    static String joinKeys(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "NA";
        }
        List<String> keys = new ArrayList<>();
        values.forEach((name, value) -> explode(value).forEach(ignored -> keys.add(name)));
        return keys.isEmpty() ? "NA" : String.join("|", keys);
    }

    static String joinValues(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "NA";
        }
        List<String> exploded = new ArrayList<>();
        values.values().forEach(value -> exploded.addAll(explode(value)));
        return exploded.isEmpty() ? "NA" : String.join("|", exploded);
    }

    static void putHeader(Map<String, String> headers, String name, String value) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
        if (value != null) {
            headers.put(name, value);
        }
    }

    private static void flatten(String prefix, Object value, List<String[]> out) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> {
                if (key == null) {
                    return;
                }
                String next = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
                flatten(next, child, out);
            });
            return;
        }
        if (value instanceof List<?> list) {
            for (Object child : list) {
                flatten(prefix, child, out);
            }
            return;
        }
        String name = prefix.isEmpty() ? "value" : prefix;
        out.add(new String[]{name, String.valueOf(value)});
    }

    private static String encode(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }
}
