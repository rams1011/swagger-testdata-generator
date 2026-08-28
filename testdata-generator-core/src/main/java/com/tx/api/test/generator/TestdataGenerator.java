package com.tx.api.test.generator;

import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.config.GeneratorOptions;
import com.tx.api.test.generator.generate.GeneratedTestCase;
import com.tx.api.test.generator.generate.SchemaCaseGenerator;
import com.tx.api.test.generator.openapi.OpenApiOperationInspector;
import com.tx.api.test.generator.openapi.OpenApiSpecLoader;
import com.tx.api.test.generator.service.SwaggerTestDataGenerator;
import io.swagger.v3.oas.models.OpenAPI;

import java.util.List;

/**
 * Library entry point for generating test data from Swagger/OpenAPI specs.
 * Used by the CLI and by testdata-generator-api.
 */
public final class TestdataGenerator {

    private TestdataGenerator() {
    }

    public static String readSpecText(String location) {
        return OpenApiSpecLoader.readRaw(location);
    }

    public static OpenAPI loadSpec(String location) {
        return OpenApiSpecLoader.load(location);
    }

    public static OpenAPI loadSpecFromContent(String content) {
        return OpenApiSpecLoader.loadFromContent(content);
    }

    public static List<OperationPage> inspect(OpenAPI openApi) {
        return inspect(openApi, null);
    }

    public static List<OperationPage> inspect(OpenAPI openApi, String baseUrlOverride) {
        return new OpenApiOperationInspector(baseUrlOverride).inspect(openApi);
    }

    public static String specTitle(OpenAPI openApi) {
        return OpenApiOperationInspector.specTitle(openApi);
    }

    public static String specVersion(OpenAPI openApi) {
        return OpenApiOperationInspector.specVersion(openApi);
    }

    public static String specDescription(OpenAPI openApi) {
        return OpenApiOperationInspector.specDescription(openApi);
    }

    public static String resolveBaseUrl(OpenAPI openApi, String baseUrlOverride) {
        return new OpenApiOperationInspector(baseUrlOverride).resolveBaseUrl(openApi);
    }

    public static void generateExcel(GeneratorOptions options) {
        new SwaggerTestDataGenerator().generate(options);
    }

    public static List<GeneratedTestCase> generateCases(OperationPage operation) {
        return new SchemaCaseGenerator().generate(operation);
    }
}
