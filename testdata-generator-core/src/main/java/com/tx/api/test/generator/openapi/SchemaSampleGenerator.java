package com.tx.api.test.generator.openapi;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class SchemaSampleGenerator {

    private static final AtomicInteger STRING_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger NUMBER_COUNTER = new AtomicInteger(1);
    private static final int MAX_ARRAY_ITEM_ASSERTIONS = 3;
    private static final int MAX_OBJECT_ASSERTIONS = 8;

    private SchemaSampleGenerator() {
    }

    public static String sampleParameterValue(Parameter parameter) {
        return sampleParameterValue(parameter, parameter != null ? parameter.getSchema() : null);
    }

    public static String sampleParameterValue(Parameter parameter, Schema<?> resolvedSchema) {
        if (parameter == null) {
            return "sample";
        }
        if (parameter.getExample() != null) {
            return String.valueOf(parameter.getExample());
        }
        if (resolvedSchema != null) {
            JsonElement sample = generateSample(resolvedSchema);
            String value = toParameterValue(sample, parameter);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return fallbackParameterValue(parameter);
    }

    private static String toParameterValue(JsonElement sample, Parameter parameter) {
        if (sample == null || sample.isJsonNull()) {
            return null;
        }
        if (sample.isJsonPrimitive()) {
            return jsonElementToAssertionValue(sample);
        }
        if (sample.isJsonArray()) {
            return sample.toString();
        }
        return fallbackParameterValue(parameter);
    }

    private static String fallbackParameterValue(Parameter parameter) {
        if ("path".equalsIgnoreCase(parameter.getIn())) {
            return samplePathValue(parameter.getName(), parameter.getSchema());
        }
        if (parameter.getSchema() != null && "integer".equals(parameter.getSchema().getType())) {
            return "1";
        }
        if (parameter.getSchema() != null && "boolean".equals(parameter.getSchema().getType())) {
            return "true";
        }
        return "sample";
    }

    public static JsonElement generateSample(Schema<?> schema) {
        if (schema == null) {
            return new JsonObject();
        }
        if (StringUtils.isNotBlank(schema.get$ref())) {
            return new JsonObject();
        }
        if (schema.getExample() != null) {
            return toJsonElement(schema.getExample());
        }
        if (schema.getDefault() != null) {
            return toJsonElement(schema.getDefault());
        }
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            return toJsonElement(schema.getEnum().get(0));
        }
        if (isBinarySchema(schema)) {
            return JsonNull.INSTANCE;
        }

        String type = schema.getType();
        if (StringUtils.isBlank(type) && schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            type = "object";
        }
        if (StringUtils.isBlank(type) && schema.getItems() != null) {
            type = "array";
        }
        if (StringUtils.isBlank(type)) {
            String sample = sampleString(schema);
            return sample == null ? JsonNull.INSTANCE : new JsonPrimitive(sample);
        }

        return switch (type) {
            case "string" -> {
                String sample = sampleString(schema);
                yield sample == null ? JsonNull.INSTANCE : new JsonPrimitive(sample);
            }
            case "integer", "number" -> new JsonPrimitive(sampleNumber(schema));
            case "boolean" -> new JsonPrimitive(true);
            case "array" -> generateArraySample(schema);
            case "object" -> generateObjectSample(schema);
            default -> new JsonObject();
        };
    }

    public static Map<String, String> buildJsonPathAssertions(Schema<?> schema) {
        Map<String, String> assertions = new LinkedHashMap<>();
        if (schema == null) {
            return assertions;
        }

        if ("array".equals(schema.getType()) && schema.getItems() != null) {
            addArrayItemAssertions(assertions, schema.getItems());
            return assertions;
        }

        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            addObjectPropertyAssertions(assertions, "$.", schema.getProperties());
        }

        return assertions;
    }

    private static void addArrayItemAssertions(Map<String, String> assertions, Schema<?> itemSchema) {
        if (itemSchema == null) {
            return;
        }
        if (itemSchema.getProperties() != null && !itemSchema.getProperties().isEmpty()) {
            int added = 0;
            for (Map.Entry<String, Schema> entry : itemSchema.getProperties().entrySet()) {
                if (added >= MAX_ARRAY_ITEM_ASSERTIONS) {
                    break;
                }
                addPropertyAssertion(assertions, "$[0]." + entry.getKey(), entry.getValue());
                added++;
            }
            return;
        }
        JsonElement sample = generateSample(itemSchema);
        if (sample != null && !sample.isJsonNull()) {
            assertions.put("$[0]", jsonElementToAssertionValue(sample));
        }
    }

    private static void addObjectPropertyAssertions(Map<String, String> assertions, String prefix,
                                                      Map<String, Schema> properties) {
        int added = 0;
        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            if (added >= MAX_OBJECT_ASSERTIONS) {
                break;
            }
            addPropertyAssertion(assertions, prefix + entry.getKey(), entry.getValue());
            added++;
        }
    }

    private static void addPropertyAssertion(Map<String, String> assertions, String path, Schema<?> propertySchema) {
        if (propertySchema == null) {
            return;
        }
        JsonElement sample = generateSample(propertySchema);
        if (sample == null || sample.isJsonNull() || sample.isJsonObject() || sample.isJsonArray()) {
            return;
        }
        assertions.put(path, jsonElementToAssertionValue(sample));
    }

    private static JsonElement generateObjectSample(Schema<?> schema) {
        JsonObject object = new JsonObject();
        if (schema.getProperties() == null) {
            return object;
        }

        schema.getProperties().forEach((propertyName, propertySchema) -> {
            if (isBinarySchema(propertySchema)) {
                return;
            }
            JsonElement value = generateSample(propertySchema);
            if (value != null) {
                object.add(propertyName, value);
            }
        });
        return object;
    }

    private static JsonElement generateArraySample(Schema<?> schema) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        if (schema.getItems() != null) {
            JsonElement item = generateSample(schema.getItems());
            if (item != null) {
                array.add(item);
            }
        }
        return array;
    }

    private static boolean isBinarySchema(Schema<?> schema) {
        if (schema == null) {
            return false;
        }
        if ("file".equalsIgnoreCase(schema.getType())) {
            return true;
        }
        if (schema.getFormat() == null) {
            return false;
        }
        String format = schema.getFormat().toLowerCase(Locale.ROOT);
        return "binary".equals(format) || "byte".equals(format);
    }

    private static String sampleString(Schema<?> schema) {
        if (schema.getFormat() != null) {
            return switch (schema.getFormat()) {
                case "date" -> LocalDate.now().format(DateTimeFormatter.ISO_DATE);
                case "date-time" -> LocalDate.now() + "T10:15:30Z";
                case "email" -> "user@example.com";
                case "uuid" -> "00000000-0000-0000-0000-000000000001";
                case "binary", "byte" -> null;
                default -> "sample-" + STRING_COUNTER.getAndIncrement();
            };
        }
        return "sample-" + STRING_COUNTER.getAndIncrement();
    }

    private static Number sampleNumber(Schema<?> schema) {
        if ("page".equalsIgnoreCase(StringUtils.defaultString(schema.getName()))
                || "pagenumber".equalsIgnoreCase(StringUtils.defaultString(schema.getName()))) {
            return 0;
        }
        if ("size".equalsIgnoreCase(StringUtils.defaultString(schema.getName()))) {
            return 10;
        }
        return NUMBER_COUNTER.getAndIncrement();
    }

    private static String samplePathValue(String name, Schema<?> schema) {
        if (schema != null) {
            if ("integer".equals(schema.getType()) || "number".equals(schema.getType())) {
                return "1";
            }
            if ("boolean".equals(schema.getType())) {
                return "true";
            }
            if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
                return String.valueOf(schema.getEnum().get(0));
            }
        }
        if (name != null && name.toLowerCase(Locale.ROOT).contains("id")) {
            return "1";
        }
        return "sample";
    }

    public static JsonElement toJsonElement(Object value) {
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof List<?> list) {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            list.forEach(item -> array.add(toJsonElement(item)));
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            map.forEach((key, mapValue) -> object.add(String.valueOf(key), toJsonElement(mapValue)));
            return object;
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    private static String jsonElementToAssertionValue(JsonElement element) {
        if (element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return String.valueOf(primitive.getAsBoolean());
            }
            if (primitive.isNumber()) {
                Number number = primitive.getAsNumber();
                double doubleValue = number.doubleValue();
                if (doubleValue == Math.rint(doubleValue)) {
                    return String.valueOf(number.longValue());
                }
            }
            return primitive.getAsString();
        }
        return element.toString();
    }
}
