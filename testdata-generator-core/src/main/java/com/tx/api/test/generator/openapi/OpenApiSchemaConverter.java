package com.tx.api.test.generator.openapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.models.media.Schema;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OpenApiSchemaConverter {

    private OpenApiSchemaConverter() {
    }

    public static JsonElement toJsonSchema(Schema<?> schema) {
        JsonElement node = toJsonSchemaNode(schema);
        if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            if (!object.has("$schema")) {
                JsonObject rooted = new JsonObject();
                rooted.addProperty("$schema", "http://json-schema.org/draft-04/schema#");
                object.entrySet().forEach(entry -> rooted.add(entry.getKey(), entry.getValue()));
                return rooted;
            }
        }
        return node;
    }

    private static JsonElement toJsonSchemaNode(Schema<?> schema) {
        if (schema == null) {
            JsonObject empty = new JsonObject();
            empty.addProperty("type", "object");
            return empty;
        }
        if (StringUtils.isNotBlank(schema.get$ref())) {
            JsonObject refSchema = new JsonObject();
            refSchema.addProperty("$ref", schema.get$ref());
            return refSchema;
        }

        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            return mergeAllOf(schema);
        }

        JsonObject jsonSchema = new JsonObject();
        String type = resolveType(schema);
        if (type != null) {
            jsonSchema.addProperty("type", type);
        }

        if (schema.getFormat() != null) {
            jsonSchema.addProperty("format", schema.getFormat());
        }
        if (schema.getTitle() != null) {
            jsonSchema.addProperty("title", schema.getTitle());
        }
        if (schema.getDescription() != null) {
            jsonSchema.addProperty("description", schema.getDescription());
        }
        if (schema.getExample() != null) {
            jsonSchema.add("example", SchemaSampleGenerator.toJsonElement(schema.getExample()));
        }
        if (schema.getDefault() != null) {
            jsonSchema.add("default", SchemaSampleGenerator.toJsonElement(schema.getDefault()));
        }
        if (schema.getPattern() != null) {
            jsonSchema.addProperty("pattern", schema.getPattern());
        }
        if (schema.getMinLength() != null) {
            jsonSchema.addProperty("minLength", schema.getMinLength());
        }
        if (schema.getMaxLength() != null) {
            jsonSchema.addProperty("maxLength", schema.getMaxLength());
        }
        if (schema.getMinimum() != null) {
            jsonSchema.addProperty("minimum", schema.getMinimum());
        }
        if (schema.getMaximum() != null) {
            jsonSchema.addProperty("maximum", schema.getMaximum());
        }
        if (schema.getMinItems() != null) {
            jsonSchema.addProperty("minItems", schema.getMinItems());
        }
        if (schema.getMaxItems() != null) {
            jsonSchema.addProperty("maxItems", schema.getMaxItems());
        }
        if (Boolean.TRUE.equals(schema.getNullable())) {
            jsonSchema.addProperty("nullable", true);
        }

        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            JsonObject properties = new JsonObject();
            for (Map.Entry<String, Schema> propertyEntry : schema.getProperties().entrySet()) {
                properties.add(propertyEntry.getKey(), toJsonSchemaNode(propertyEntry.getValue()));
            }
            jsonSchema.add("properties", properties);
            if (schema.getRequired() != null && !schema.getRequired().isEmpty()) {
                JsonArray requiredArray = new JsonArray();
                schema.getRequired().forEach(requiredArray::add);
                jsonSchema.add("required", requiredArray);
            }
        }

        if (schema.getItems() != null) {
            jsonSchema.add("items", toJsonSchemaNode(schema.getItems()));
        }

        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            JsonArray enumValues = new JsonArray();
            schema.getEnum().forEach(value -> enumValues.add(SchemaSampleGenerator.toJsonElement(value)));
            jsonSchema.add("enum", enumValues);
        }

        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            JsonArray oneOf = new JsonArray();
            schema.getOneOf().forEach(part -> oneOf.add(toJsonSchemaNode(part)));
            jsonSchema.add("oneOf", oneOf);
        }
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            JsonArray anyOf = new JsonArray();
            schema.getAnyOf().forEach(part -> anyOf.add(toJsonSchemaNode(part)));
            jsonSchema.add("anyOf", anyOf);
        }

        return jsonSchema;
    }

    @SuppressWarnings("rawtypes")
    private static JsonObject mergeAllOf(Schema<?> schema) {
        JsonObject merged = new JsonObject();
        merged.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        Set<String> required = new LinkedHashSet<>();
        List<Schema> parts = new ArrayList<>();
        parts.addAll(schema.getAllOf());
        if (schema.getProperties() != null) {
            parts.add(schema);
        }
        for (Schema<?> part : parts) {
            JsonElement partNode = part == schema
                    ? propertiesOnly(schema)
                    : toJsonSchemaNode(part);
            if (!partNode.isJsonObject()) {
                continue;
            }
            JsonObject object = partNode.getAsJsonObject();
            if (object.has("properties")) {
                object.getAsJsonObject("properties").entrySet()
                        .forEach(entry -> properties.add(entry.getKey(), entry.getValue()));
            }
            if (object.has("required")) {
                object.getAsJsonArray("required").forEach(value -> required.add(value.getAsString()));
            }
        }
        if (properties.size() > 0) {
            merged.add("properties", properties);
        }
        if (!required.isEmpty()) {
            JsonArray requiredArray = new JsonArray();
            required.forEach(requiredArray::add);
            merged.add("required", requiredArray);
        }
        if (schema.getDescription() != null) {
            merged.addProperty("description", schema.getDescription());
        }
        return merged;
    }

    private static JsonObject propertiesOnly(Schema<?> schema) {
        JsonObject object = new JsonObject();
        if (schema.getProperties() == null) {
            return object;
        }
        JsonObject properties = new JsonObject();
        schema.getProperties().forEach((name, propertySchema) ->
                properties.add(name, toJsonSchemaNode(propertySchema)));
        object.add("properties", properties);
        if (schema.getRequired() != null) {
            JsonArray requiredArray = new JsonArray();
            schema.getRequired().forEach(requiredArray::add);
            object.add("required", requiredArray);
        }
        return object;
    }

    private static String resolveType(Schema<?> schema) {
        if (schema.getType() != null) {
            return schema.getType();
        }
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            return "object";
        }
        if (schema.getItems() != null) {
            return "array";
        }
        if (schema.getFormat() != null) {
            return typeFromFormat(schema.getFormat());
        }
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            Object first = schema.getEnum().get(0);
            if (first instanceof Number) {
                return first instanceof Integer || first instanceof Long ? "integer" : "number";
            }
            if (first instanceof Boolean) {
                return "boolean";
            }
            return "string";
        }
        if (schema.getPattern() != null || schema.getMinLength() != null || schema.getMaxLength() != null) {
            return "string";
        }
        return "string";
    }

    private static String typeFromFormat(String format) {
        return switch (format) {
            case "int32", "int64" -> "integer";
            case "float", "double" -> "number";
            default -> "string";
        };
    }
}
