package com.tx.api.test.generator;

import com.tx.api.test.generator.config.GeneratorOptions;
import com.tx.api.test.generator.service.SwaggerTestDataGenerator;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "swagger-testdata-generator",
        description = "Generate api-automation TestData.xlsx from Swagger/OpenAPI specifications",
        mixinStandardHelpOptions = true
)
public final class SwaggerTestDataGeneratorApp implements Runnable {

    @Option(
            names = {"-s", "--swagger"},
            required = true,
            description = "Swagger/OpenAPI spec URL or local file path (.json/.yaml/.yml)"
    )
    private String swaggerLocation;

    @Option(
            names = {"-o", "--output"},
            description = "Output Excel file path (default: TestData.xlsx in current directory)"
    )
    private String outputFile;

    @Option(
            names = {"-b", "--base-url"},
            description = "Override base URL written to the Service Base Url column"
    )
    private String baseUrlOverride;

    @Option(
            names = {"-g", "--test-group"},
            description = "Default TestGroup value when operation has no tags (default: generated)"
    )
    private String defaultTestGroup;

    @Option(
            names = {"-e", "--enabled"},
            description = "Enabled column value for generated rows (default: Y)"
    )
    private String enabled = "Y";

    @Option(
            names = {"-t", "--max-response-time"},
            description = "MaxResponseTimeMs column value (default: 3000)"
    )
    private long maxResponseTimeMs = 3000L;

    @Option(
            names = {"-p", "--payload-dir"},
            description = "Directory for generated request JSON payloads (default: payloads next to output file)"
    )
    private String payloadDirectory;

    @Option(
            names = {"--schema-dir"},
            description = "Directory for generated response JSON schemas (default: schemas next to output file)"
    )
    private String schemaDirectory;

    @Option(
            names = {"--payload-prefix"},
            description = "Path prefix used in file: references for payloads (default: src/test/data/payloads)"
    )
    private String payloadPathPrefix = "src/test/data/payloads";

    @Option(
            names = {"--schema-prefix"},
            description = "Path prefix used in file: references for schemas (default: src/test/data/schemas)"
    )
    private String schemaPathPrefix = "src/test/data/schemas";

    @Option(
            names = {"--include"},
            description = "Include only operations whose path or operationId matches this regex (optional)"
    )
    private String includePattern;

    @Option(
            names = {"--exclude"},
            description = "Exclude operations whose path or operationId matches this regex (optional)"
    )
    private String excludePattern;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SwaggerTestDataGeneratorApp()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        GeneratorOptions options = GeneratorOptions.builder()
                .swaggerLocation(swaggerLocation)
                .outputFile(outputFile)
                .baseUrlOverride(baseUrlOverride)
                .defaultTestGroup(defaultTestGroup)
                .enabled(enabled)
                .maxResponseTimeMs(maxResponseTimeMs)
                .payloadDirectory(payloadDirectory)
                .schemaDirectory(schemaDirectory)
                .payloadPathPrefix(payloadPathPrefix)
                .schemaPathPrefix(schemaPathPrefix)
                .includePattern(includePattern)
                .excludePattern(excludePattern)
                .build();

        SwaggerTestDataGenerator generator = new SwaggerTestDataGenerator();
        generator.generate(options);
    }
}
