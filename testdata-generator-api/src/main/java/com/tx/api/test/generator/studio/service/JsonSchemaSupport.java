package com.tx.api.test.generator.studio.service;

import com.google.gson.*;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Adapts OpenAPI response schemas the same way the JDBC runner does before draft-04 validation.
 */
final class JsonSchemaSupport {

    private static final Gson JSON = new Gson();
    private static final String ISO_LOCAL_OR_OFFSET =
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$";

    private JsonSchemaSupport() {
    }

    static String forValidation(String schemaJson) {
        if (StringUtils.isBlank(schemaJson)) {
            return schemaJson;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(schemaJson);
        } catch (RuntimeException exception) {
            return schemaJson;
        }
        adapt(root);
        return JSON.toJson(root);
    }

    private static void adapt(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject schema = element.getAsJsonObject();
        if (nullable(schema)) {
            allowNull(schema);
        }
        relaxDateTime(schema);
        dropOpenApiNumericFormats(schema);

        if (schema.has("properties") && schema.get("properties").isJsonObject()) {
            Set<String> required = requiredNames(schema);
            JsonObject properties = schema.getAsJsonObject("properties");
            for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject property = entry.getValue().getAsJsonObject();
                if (!required.contains(entry.getKey())) {
                    allowNull(property);
                }
                adapt(property);
            }
        }
        adaptChild(schema, "items");
        adaptChild(schema, "additionalProperties");
        adaptArray(schema, "oneOf");
        adaptArray(schema, "anyOf");
        adaptArray(schema, "allOf");
    }

    private static void adaptChild(JsonObject schema, String name) {
        if (schema.has(name)) {
            adapt(schema.get(name));
        }
    }

    private static void adaptArray(JsonObject schema, String name) {
        if (!schema.has(name) || !schema.get(name).isJsonArray()) {
            return;
        }
        schema.getAsJsonArray(name).forEach(JsonSchemaSupport::adapt);
    }

    private static Set<String> requiredNames(JsonObject schema) {
        Set<String> required = new LinkedHashSet<>();
        if (!schema.has("required") || !schema.get("required").isJsonArray()) {
            return required;
        }
        schema.getAsJsonArray("required").forEach(item -> {
            if (item.isJsonPrimitive()) {
                required.add(item.getAsString());
            }
        });
        return required;
    }

    private static boolean nullable(JsonObject schema) {
        return schema.has("nullable")
                && schema.get("nullable").isJsonPrimitive()
                && schema.get("nullable").getAsBoolean();
    }

    private static void allowNull(JsonObject schema) {
        if (!schema.has("type")) {
            return;
        }
        JsonElement type = schema.get("type");
        if (type.isJsonPrimitive()) {
            String value = type.getAsString();
            if ("null".equals(value)) {
                return;
            }
            JsonArray types = new JsonArray();
            types.add(value);
            types.add("null");
            schema.add("type", types);
            return;
        }
        if (!type.isJsonArray()) {
            return;
        }
        for (JsonElement item : type.getAsJsonArray()) {
            if (item.isJsonPrimitive() && "null".equals(item.getAsString())) {
                return;
            }
        }
        type.getAsJsonArray().add("null");
    }

    private static void relaxDateTime(JsonObject schema) {
        if (!schema.has("format") || !schema.get("format").isJsonPrimitive()) {
            return;
        }
        if (!"date-time".equals(schema.get("format").getAsString())) {
            return;
        }
        schema.remove("format");
        if (!schema.has("pattern")) {
            schema.addProperty("pattern", ISO_LOCAL_OR_OFFSET);
        }
    }

    private static void dropOpenApiNumericFormats(JsonObject schema) {
        if (!schema.has("format") || !schema.get("format").isJsonPrimitive()) {
            return;
        }
        String format = schema.get("format").getAsString();
        if ("int32".equals(format) || "int64".equals(format) || "float".equals(format) || "double".equals(format)) {
            schema.remove("format");
        }
    }
}
