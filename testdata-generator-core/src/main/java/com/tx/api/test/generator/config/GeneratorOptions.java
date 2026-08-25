package com.tx.api.test.generator.config;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GeneratorOptions {
    private final String swaggerLocation;
    private final String outputFile;
    private final String baseUrlOverride;
    private final String defaultTestGroup;
    private final String enabled;
    private final long maxResponseTimeMs;
    private final String payloadDirectory;
    private final String schemaDirectory;
    private final String payloadPathPrefix;
    private final String schemaPathPrefix;
    private final String includePattern;
    private final String excludePattern;
}
