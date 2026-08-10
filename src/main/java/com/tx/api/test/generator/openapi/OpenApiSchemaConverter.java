package com.tx.api.test.generator.openapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.models.media.Schema;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class OpenApiSchemaConverter {

    private OpenApiSchemaConverter() {
    }

    public static JsonElement toJsonSchema(Schema<?> schema) {
        if (schema == null) {
            return new JsonObject();
        }
        if (StringUtils.isNotBlank(schema.get$ref())) {
            JsonObject refSchema = new JsonObject();
            refSchema.addProperty("$schema", "http://json-schema.org/draft-04/schema#");
            refSchema.addProperty("$ref", schema.get$ref());
            return refSchema;
        }

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("$schema", "http://json-schema.org/draft-04/schema#");

        if (schema.getType() != null) {
            jsonSchema.addProperty("type", schema.getType());
        } else if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            jsonSchema.addProperty("type", "object");
        }

        if (schema.getFormat() != null) {
            jsonSchema.addProperty("format", schema.getFormat());
        }
        if (schema.getDescription() != null) {
            jsonSchema.addProperty("description", schema.getDescription());
        }
        if (schema.getExample() != null) {
            jsonSchema.add("example", SchemaSampleGenerator.toJsonElement(schema.getExample()));
        }

        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            JsonObject properties = new JsonObject();
            List<String> required = new ArrayList<>();

            for (Map.Entry<String, Schema> propertyEntry : schema.getProperties().entrySet()) {
                properties.add(propertyEntry.getKey(), toJsonSchema(propertyEntry.getValue()));
                if (schema.getRequired() != null && schema.getRequired().contains(propertyEntry.getKey())) {
                    required.add(propertyEntry.getKey());
                }
            }

            jsonSchema.add("properties", properties);
            if (!required.isEmpty()) {
                JsonArray requiredArray = new JsonArray();
                required.forEach(requiredArray::add);
                jsonSchema.add("required", requiredArray);
            }
        }

        if (schema.getItems() != null) {
            jsonSchema.add("items", toJsonSchema(schema.getItems()));
        }

        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            JsonArray enumValues = new JsonArray();
            schema.getEnum().forEach(value -> enumValues.add(SchemaSampleGenerator.toJsonElement(value)));
            jsonSchema.add("enum", enumValues);
        }

        return jsonSchema;
    }
}
