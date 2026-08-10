package com.tx.api.test.generator.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SchemaResolver {

    private final OpenAPI openAPI;

    public SchemaResolver(OpenAPI openAPI) {
        this.openAPI = openAPI;
    }

    public Schema<?> resolve(Schema<?> schema) {
        if (schema == null) {
            return null;
        }
        if (StringUtils.isNotBlank(schema.get$ref())) {
            Schema<?> resolved = resolveRef(schema.get$ref());
            return resolved != null ? resolve(resolved) : schema;
        }
        return resolveNested(schema);
    }

    private Schema<?> resolveNested(Schema<?> schema) {
        if (schema.getProperties() != null) {
            Map<String, Schema> resolvedProperties = new LinkedHashMap<>();
            schema.getProperties().forEach((name, propertySchema) ->
                    resolvedProperties.put(name, resolve(propertySchema)));
            schema.setProperties(resolvedProperties);
        }
        if (schema.getItems() != null) {
            schema.setItems(resolve(schema.getItems()));
        }
        if (schema.getAdditionalProperties() instanceof Schema<?> additionalSchema) {
            schema.setAdditionalProperties(resolve(additionalSchema));
        }
        return schema;
    }

    private Schema<?> resolveRef(String ref) {
        if (StringUtils.isBlank(ref) || !ref.startsWith("#/")) {
            return null;
        }
        String[] parts = ref.substring(2).split("/");
        if (parts.length < 3 || !"components".equals(parts[0])) {
            return null;
        }

        return switch (parts[1]) {
            case "schemas" -> getComponentSchema(parts[2]);
            default -> null;
        };
    }

    @SuppressWarnings("rawtypes")
    private Schema<?> getComponentSchema(String schemaName) {
        if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return null;
        }
        return openAPI.getComponents().getSchemas().get(schemaName);
    }
}
