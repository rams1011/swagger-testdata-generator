package com.tx.api.test.generator.generate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonSchemaMaps {

    private JsonSchemaMaps() {
    }

    @SuppressWarnings("unchecked")
    static Object copy(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), copy(nested)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(copy(item));
            }
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    static String type(Map<String, Object> schema) {
        if (schema == null) {
            return null;
        }
        String fromType = firstType(schema.get("type"));
        if (fromType != null) {
            return fromType;
        }
        String fromTypes = firstType(schema.get("types"));
        if (fromTypes != null) {
            return fromTypes;
        }
        if (schema.get("properties") instanceof Map<?, ?>) {
            return "object";
        }
        if (schema.containsKey("items")) {
            return "array";
        }
        return null;
    }

    static boolean isObject(Map<String, Object> schema) {
        String type = type(schema);
        return "object".equals(type) || (schema != null && schema.get("properties") instanceof Map<?, ?>);
    }

    static boolean isInteger(Map<String, Object> schema) {
        String type = type(schema);
        String format = stringValue(schema, "format");
        return "integer".equals(type) || "int32".equals(format) || "int64".equals(format);
    }

    static boolean isNumber(Map<String, Object> schema) {
        return isInteger(schema) || "number".equals(type(schema));
    }

    static boolean isBoolean(Map<String, Object> schema) {
        return "boolean".equals(type(schema));
    }

    static boolean isString(Map<String, Object> schema) {
        return "string".equals(type(schema));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> properties(Map<String, Object> schema) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> map)) {
            return result;
        }
        map.forEach((key, value) -> {
            if (value instanceof Map<?, ?> property) {
                result.put(String.valueOf(key), (Map<String, Object>) property);
            }
        });
        return result;
    }

    static List<String> required(Map<String, Object> schema) {
        List<String> names = new ArrayList<>();
        if (schema == null || !(schema.get("required") instanceof List<?> list)) {
            return names;
        }
        for (Object item : list) {
            if (item != null) {
                names.add(String.valueOf(item));
            }
        }
        return names;
    }

    static List<Object> enums(Map<String, Object> schema) {
        if (schema == null) {
            return List.of();
        }
        if (schema.get("enum") instanceof List<?> list && !list.isEmpty()) {
            return new ArrayList<>(list);
        }
        Map<String, Object> items = items(schema);
        return items == null ? List.of() : enums(items);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> items(Map<String, Object> schema) {
        if (schema == null || !(schema.get("items") instanceof Map<?, ?> items)) {
            return null;
        }
        return (Map<String, Object>) items;
    }

    static Integer intConstraint(Map<String, Object> schema, String key) {
        Number number = numberConstraint(schema, key);
        return number == null ? null : number.intValue();
    }

    static Number numberConstraint(Map<String, Object> schema, String key) {
        if (schema == null) {
            return null;
        }
        Object raw = schema.get(key);
        if (raw instanceof Number number) {
            return number;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static boolean additionalPropertiesForbidden(Map<String, Object> schema) {
        return schema != null && Boolean.FALSE.equals(schema.get("additionalProperties"));
    }

    private static String stringValue(Map<String, Object> schema, String key) {
        if (schema == null || schema.get(key) == null) {
            return null;
        }
        return String.valueOf(schema.get(key));
    }

    private static String firstType(Object raw) {
        if (raw instanceof String text && !text.isBlank()) {
            return text;
        }
        if (raw instanceof List<?> list && !list.isEmpty()) {
            for (String preferred : List.of("object", "array", "integer", "number", "boolean", "string")) {
                for (Object item : list) {
                    if (preferred.equals(String.valueOf(item))) {
                        return preferred;
                    }
                }
            }
            return String.valueOf(list.get(0));
        }
        return null;
    }
}
