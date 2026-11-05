package com.tx.api.test.generator;

import com.tx.api.test.generator.config.GeneratorOptions;
import com.tx.api.test.generator.service.CollectionGenerator;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "swagger-testdata-generator",
        description = "Generate a Studio collection JSON (testdata.studio.collection v1) and environment JSON "
                + "from a Swagger/OpenAPI spec, for api-automation or import into Test Data Studio",
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
            description = "Collection JSON path (default: <spec>.<plan>.collection.json in the current directory)"
    )
    private String outputFile;

    @Option(
            names = {"--environment-output"},
            description = "Environment JSON path (default: <spec>.<environment>.environment.json next to the collection)"
    )
    private String environmentOutputFile;

    @Option(
            names = {"-b", "--base-url"},
            description = "Override the base URL written to the spec and environment file"
    )
    private String baseUrlOverride;

    @Option(
            names = {"--plan"},
            description = "Plan name in the collection (default: Default)"
    )
    private String planName = "Default";

    @Option(
            names = {"--environment"},
            description = "Environment name in the environment file (default: Default)"
    )
    private String environmentName = "Default";

    @Option(
            names = {"--auth-type"},
            description = "Environment auth: none, bearer, basic, api_key, oauth2 (default: from the spec's security)"
    )
    private String authType;

    @Option(
            names = {"--negative-cases"},
            description = "Also generate negative cases (missing required fields, invalid enum/type, 404, 401, ...)"
    )
    private boolean includeNegativeCases;

    @Option(
            names = {"-g", "--test-group"},
            description = "Test group when an operation has no tags (default: generated)"
    )
    private String defaultTestGroup;

    @Option(
            names = {"-e", "--enabled"},
            description = "Enabled value for generated cases (default: Y)"
    )
    private String enabled = "Y";

    @Option(
            names = {"-t", "--max-response-time"},
            description = "maxResponseTimeMs for generated cases (default: 3000)"
    )
    private long maxResponseTimeMs = 3000L;

    @Option(
            names = {"--include"},
            description = "Include only operations whose path, operationId, or summary matches this regex"
    )
    private String includePattern;

    @Option(
            names = {"--exclude"},
            description = "Exclude operations whose path, operationId, or summary matches this regex"
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
                .environmentOutputFile(environmentOutputFile)
                .baseUrlOverride(baseUrlOverride)
                .planName(planName)
                .environmentName(environmentName)
                .authType(authType)
                .includeNegativeCases(includeNegativeCases)
                .defaultTestGroup(defaultTestGroup)
                .enabled(enabled)
                .maxResponseTimeMs(maxResponseTimeMs)
                .includePattern(includePattern)
                .excludePattern(excludePattern)
                .build();

        CollectionGenerator.Result result = TestdataGenerator.generateCollection(options);
        System.out.println("Generated " + result.caseCount() + " cases for " + result.operationCount() + " operations");
        System.out.println("Collection: " + result.collectionFile().toAbsolutePath());
        System.out.println("Environment: " + result.environmentFile().toAbsolutePath());
    }
}
