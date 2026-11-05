package com.tx.api.test.generator.config;

import lombok.Builder;
import lombok.Getter;

/** Options for the offline generator: OpenAPI spec in, Studio collection JSON (+ environment JSON) out. */
@Getter
@Builder
public class GeneratorOptions {
    private final String swaggerLocation;
    /** Collection file; default {@code <spec>.<plan>.collection.json} in the current directory. */
    private final String outputFile;
    /** Environment file; default {@code <spec>.<environment>.environment.json} next to the collection. */
    private final String environmentOutputFile;
    private final String baseUrlOverride;
    private final String defaultTestGroup;
    private final String enabled;
    private final long maxResponseTimeMs;
    private final String includePattern;
    private final String excludePattern;
    private final String planName;
    private final String environmentName;
    /** NONE, BEARER, BASIC, API_KEY, OAUTH2; blank derives it from the spec's security schemes. */
    private final String authType;
    /** Also write generated negative cases (missing fields, invalid enum/type, 404, 401, ...). */
    private final boolean includeNegativeCases;
}
