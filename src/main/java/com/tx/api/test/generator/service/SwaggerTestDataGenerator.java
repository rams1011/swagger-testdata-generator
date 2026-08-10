package com.tx.api.test.generator.service;

import com.tx.api.test.generator.config.GeneratorOptions;
import com.tx.api.test.generator.model.TestDataRow;
import com.tx.api.test.generator.openapi.OpenApiSpecLoader;
import com.tx.api.test.generator.openapi.OpenApiTestDataMapper;
import com.tx.api.test.generator.writer.TestDataExcelWriter;
import io.swagger.v3.oas.models.OpenAPI;

import java.nio.file.Path;
import java.util.List;

public final class SwaggerTestDataGenerator {

    public void generate(GeneratorOptions options) {
        OpenAPI openApi = OpenApiSpecLoader.load(options.getSwaggerLocation());
        Path outputPath = resolveOutputPath(options);
        Path payloadDir = resolvePayloadDirectory(options, outputPath);
        Path schemaDir = resolveSchemaDirectory(options, outputPath);

        OpenApiTestDataMapper mapper = new OpenApiTestDataMapper(options, payloadDir, schemaDir);
        List<TestDataRow> rows = mapper.map(openApi);

        if (rows.isEmpty()) {
            throw new IllegalStateException("No operations matched the Swagger spec and filter criteria.");
        }

        TestDataExcelWriter writer = new TestDataExcelWriter();
        writer.write(outputPath, rows);

        System.out.println("Generated " + rows.size() + " test rows");
        System.out.println("Excel output: " + outputPath.toAbsolutePath());
        System.out.println("Payload directory: " + payloadDir.toAbsolutePath());
        System.out.println("Schema directory: " + schemaDir.toAbsolutePath());
    }

    private Path resolveOutputPath(GeneratorOptions options) {
        if (options.getOutputFile() != null && !options.getOutputFile().isBlank()) {
            return Path.of(options.getOutputFile().trim());
        }
        return Path.of("TestData.xlsx");
    }

    private Path resolvePayloadDirectory(GeneratorOptions options, Path outputPath) {
        if (options.getPayloadDirectory() != null && !options.getPayloadDirectory().isBlank()) {
            return Path.of(options.getPayloadDirectory().trim());
        }
        Path parent = outputPath.getParent();
        if (parent == null) {
            return Path.of("payloads");
        }
        return parent.resolve("payloads");
    }

    private Path resolveSchemaDirectory(GeneratorOptions options, Path outputPath) {
        if (options.getSchemaDirectory() != null && !options.getSchemaDirectory().isBlank()) {
            return Path.of(options.getSchemaDirectory().trim());
        }
        Path parent = outputPath.getParent();
        if (parent == null) {
            return Path.of("schemas");
        }
        return parent.resolve("schemas");
    }
}
