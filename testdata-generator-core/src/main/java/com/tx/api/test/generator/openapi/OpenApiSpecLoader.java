package com.tx.api.test.generator.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.converter.SwaggerConverter;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import io.swagger.parser.util.SwaggerDeserializationResult;
import io.swagger.parser.SwaggerParser;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Collectors;

public final class OpenApiSpecLoader {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private OpenApiSpecLoader() {
    }

    public static OpenAPI loadFromContent(String specContent) {
        if (StringUtils.isBlank(specContent)) {
            throw new IllegalArgumentException("Swagger/OpenAPI content must not be blank");
        }
        String suffix = looksLikeJson(specContent) ? ".json" : ".yaml";
        try {
            Path tempFile = Files.createTempFile("openapi-spec-", suffix);
            try {
                Files.writeString(tempFile, specContent, StandardCharsets.UTF_8);
                return load(tempFile.toAbsolutePath().toString());
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to parse uploaded Swagger/OpenAPI content", exception);
        }
    }

    public static OpenAPI load(String location) {
        if (StringUtils.isBlank(location)) {
            throw new IllegalArgumentException("Swagger location must not be blank");
        }

        String trimmedLocation = location.trim();
        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        parseOptions.setResolveFully(true);

        SwaggerParseResult parseResult = new OpenAPIV3Parser().readLocation(trimmedLocation, null, parseOptions);
        if (parseResult.getOpenAPI() != null) {
            return parseResult.getOpenAPI();
        }

        String specContent = readSpecContent(trimmedLocation);
        if (isSwagger2Document(specContent)) {
            SwaggerDeserializationResult swaggerResult = new SwaggerParser().readWithInfo(specContent);
            if (swaggerResult == null || swaggerResult.getSwagger() == null) {
                throw new IllegalArgumentException("Failed to parse Swagger 2.0 document: " + trimmedLocation);
            }
            SwaggerParseResult converted = new SwaggerConverter().convert(swaggerResult);
            if (converted.getOpenAPI() == null) {
                throw new IllegalArgumentException("Failed to convert Swagger 2.0 document: " + trimmedLocation);
            }
            return converted.getOpenAPI();
        }

        String messages = parseResult.getMessages() == null
                ? "Unknown parse error"
                : parseResult.getMessages().stream().collect(Collectors.joining("; "));
        throw new IllegalArgumentException("Failed to parse Swagger/OpenAPI spec: " + messages);
    }

    public static String readRaw(String location) {
        if (StringUtils.isBlank(location)) {
            throw new IllegalArgumentException("Swagger location must not be blank");
        }
        return readSpecContent(location.trim());
    }

    private static boolean isSwagger2Document(String specContent) {
        return specContent.contains("\"swagger\"")
                && (specContent.contains("\"2.0\"") || specContent.contains("'2.0'"));
    }

    private static String readSpecContent(String location) {
        if (isRemoteLocation(location)) {
            return readRemoteContent(location);
        }
        try {
            return Files.readString(Path.of(location), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read Swagger/OpenAPI file: " + location, exception);
        }
    }

    private static String readRemoteContent(String location) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(location))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalArgumentException("Failed to download Swagger/OpenAPI spec. HTTP "
                        + response.statusCode() + " for " + location);
            }
            return response.body();
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Failed to download Swagger/OpenAPI spec: " + location, exception);
        }
    }

    private static boolean isRemoteLocation(String location) {
        return location.startsWith("http://") || location.startsWith("https://");
    }

    private static boolean looksLikeJson(String specContent) {
        String trimmed = specContent.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }
}
