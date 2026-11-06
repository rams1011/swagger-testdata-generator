package com.tx.api.test.generator.openapi;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.converter.SwaggerConverter;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import io.swagger.parser.util.SwaggerDeserializationResult;
import io.swagger.parser.SwaggerParser;
import org.apache.commons.lang3.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class OpenApiSpecLoader {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final JsonFactory JSON_TOKENS = new JsonFactory();
    private static final YAMLFactory YAML_TOKENS = yamlTokens();
    private static final Pattern MAPPING_SCHEMA_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");
    private static final Pattern LOCATION_TEXT = Pattern.compile("(?i)(file:|https?://)\\S+|[A-Za-z]:[/\\\\][^\\s;]+");

    private OpenApiSpecLoader() {
    }

    public static OpenAPI loadFromContent(String specContent) {
        if (StringUtils.isBlank(specContent)) {
            throw new IllegalArgumentException("Swagger/OpenAPI content must not be blank");
        }
        rejectExternalReferences(specContent);
        SwaggerParseResult parseResult = new OpenAPIV3Parser().readContents(specContent, null, contentParseOptions());
        if (parseResult.getOpenAPI() != null) {
            return parseResult.getOpenAPI();
        }
        if (isSwagger2Document(specContent)) {
            return convertSwagger2(specContent, "uploaded content");
        }
        throw parseFailure(parseResult);
    }

    public static OpenAPI load(String location) {
        if (StringUtils.isBlank(location)) {
            throw new IllegalArgumentException("Swagger location must not be blank");
        }

        String trimmedLocation = location.trim();
        if (isRemoteLocation(trimmedLocation)) {
            return loadFromContent(readRemoteContent(trimmedLocation));
        }
        try {
            if (!Files.exists(Path.of(trimmedLocation))) {
                throw new IllegalArgumentException("The spec file could not be read.");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("The spec file could not be read.", exception);
        }
        SwaggerParseResult parseResult;
        try {
            parseResult = new OpenAPIV3Parser()
                    .readLocation(toParserLocation(trimmedLocation), null, parseOptions());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("The spec file could not be read.", exception);
        }
        if (parseResult.getOpenAPI() != null) {
            return parseResult.getOpenAPI();
        }

        String specContent = readSpecContent(trimmedLocation);
        if (isSwagger2Document(specContent)) {
            return convertSwagger2(specContent, "the spec");
        }
        throw parseFailure(parseResult);
    }

    public static String readRaw(String location) {
        if (StringUtils.isBlank(location)) {
            throw new IllegalArgumentException("Swagger location must not be blank");
        }
        return readSpecContent(location.trim());
    }

    private static OpenAPI convertSwagger2(String specContent, String source) {
        SwaggerDeserializationResult swaggerResult = new SwaggerParser().readWithInfo(specContent);
        if (swaggerResult == null || swaggerResult.getSwagger() == null) {
            throw new IllegalArgumentException("Failed to parse Swagger 2.0 document: " + source);
        }
        SwaggerParseResult converted = new SwaggerConverter().convert(swaggerResult);
        if (converted.getOpenAPI() == null) {
            throw new IllegalArgumentException("Failed to convert Swagger 2.0 document: " + source);
        }
        return converted.getOpenAPI();
    }

    private static ParseOptions parseOptions() {
        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        parseOptions.setResolveFully(true);
        return parseOptions;
    }

    /** Content can come from a user, so the parser must not fetch anything even if a reference gets past the scan. */
    static ParseOptions contentParseOptions() {
        ParseOptions parseOptions = parseOptions();
        parseOptions.setSafelyResolveURL(true);
        parseOptions.setRemoteRefBlockList(List.of("*"));
        return parseOptions;
    }

    /**
     * swagger-parser 3.1 dereference builds a java.net.URI from the location.
     * A Windows path such as C:\Users\... is not a URI (colon at index 2).
     */
    static String toParserLocation(String location) {
        if (isRemoteLocation(location) || location.regionMatches(true, 0, "file:", 0, 5)) {
            return location;
        }
        try {
            Path path = Path.of(location);
            if (Files.exists(path)) {
                return path.toAbsolutePath().normalize().toUri().toASCIIString();
            }
        } catch (RuntimeException ignored) {
            return location;
        }
        return location;
    }

    private static IllegalArgumentException parseFailure(SwaggerParseResult parseResult) {
        String messages = parseResult.getMessages() == null
                ? ""
                : parseResult.getMessages().stream().collect(Collectors.joining("; "));
        String cleaned = LOCATION_TEXT.matcher(messages).replaceAll("").replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank()) {
            return new IllegalArgumentException("The spec could not be read.");
        }
        return new IllegalArgumentException("Failed to parse Swagger/OpenAPI spec: " + cleaned);
    }

    /**
     * Refuses a {@code $ref} or discriminator mapping that points outside the document, since the parser would fetch
     * it. This reads the same tokens the parser does (the parser treats text starting with a brace as JSON and anything
     * else as YAML), so escapes, quoting, flow style and list items cannot hide a reference. A YAML alias in one of
     * these places is refused because its target is not visible where it is used. Text that cannot be tokenized is
     * refused too, so nothing the scan could not read reaches the parser.
     */
    static void rejectExternalReferences(String content) {
        boolean externalReference;
        try (JsonParser parser = content.trim().startsWith("{")
                ? JSON_TOKENS.createParser(content)
                : YAML_TOKENS.createParser(content)) {
            externalReference = hasExternalReference(parser);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Failed to parse Swagger/OpenAPI spec: the document is not valid "
                    + "JSON or YAML.");
        }
        if (externalReference) {
            throw new IllegalArgumentException(
                    "The spec must be a single document. Remote or file references are not allowed.");
        }
    }

    private static boolean hasExternalReference(JsonParser parser) throws IOException {
        Deque<String> enclosing = new ArrayDeque<>();
        String field = null;
        for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
            if (token == JsonToken.FIELD_NAME) {
                field = parser.currentName();
                continue;
            }
            if ("$ref".equals(field)) {
                if (token != JsonToken.VALUE_STRING || isAlias(parser)
                        || !parser.getText().trim().startsWith("#")) {
                    return true;
                }
            } else if (isAlias(parser) && inDiscriminator(enclosing, field)) {
                return true;
            } else if (token == JsonToken.VALUE_STRING && inMapping(enclosing)) {
                String value = parser.getText().trim();
                if (!value.startsWith("#") && !MAPPING_SCHEMA_NAME.matcher(value).matches()) {
                    return true;
                }
            }
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                enclosing.push(field == null ? "" : field);
            } else if ((token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) && !enclosing.isEmpty()) {
                enclosing.pop();
            }
            field = null;
        }
        return false;
    }

    private static boolean isAlias(JsonParser parser) {
        return parser instanceof YAMLParser yaml && yaml.isCurrentAlias();
    }

    private static boolean inDiscriminator(Deque<String> enclosing, String field) {
        return "discriminator".equals(field)
                || "discriminator".equals(enclosing.peek())
                || inMapping(enclosing);
    }

    private static boolean inMapping(Deque<String> enclosing) {
        if (!"mapping".equals(enclosing.peek())) {
            return false;
        }
        Iterator<String> outward = enclosing.iterator();
        outward.next();
        return outward.hasNext() && "discriminator".equals(outward.next());
    }

    private static YAMLFactory yamlTokens() {
        // Only tokens are read here, so the default limit of about 3 MB would just refuse large specs.
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);
        return YAMLFactory.builder().loaderOptions(options).build();
    }

    private static boolean isSwagger2Document(String specContent) {
        if (StringUtils.isBlank(specContent)) {
            return false;
        }
        if (specContent.contains("\"swagger\"")
                && (specContent.contains("\"2.0\"") || specContent.contains("'2.0'"))) {
            return true;
        }
        // YAML (e.g. swagger: "2.0" at root) — not detected by JSON key heuristics above.
        for (String line : specContent.split("\\R", 32)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.regionMatches(true, 0, "openapi:", 0, "openapi:".length())
                    || trimmed.regionMatches(true, 0, "openapi ", 0, "openapi ".length())) {
                return false;
            }
            if (trimmed.regionMatches(true, 0, "swagger:", 0, "swagger:".length())) {
                String value = trimmed.substring("swagger:".length()).trim();
                return value.equals("2.0") || value.equals("\"2.0\"") || value.equals("'2.0'");
            }
            if (!trimmed.startsWith("---") && !trimmed.regionMatches(true, 0, "info:", 0, "info:".length())) {
                break;
            }
        }
        return false;
    }

    private static String readSpecContent(String location) {
        if (isRemoteLocation(location)) {
            return readRemoteContent(location);
        }
        try {
            return Files.readString(Path.of(location), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("The spec file could not be read.", exception);
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
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("The spec could not be downloaded.");
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("The spec could not be downloaded.", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("The spec could not be downloaded.", exception);
        }
    }

    private static boolean isRemoteLocation(String location) {
        return location.regionMatches(true, 0, "http://", 0, "http://".length())
                || location.regionMatches(true, 0, "https://", 0, "https://".length());
    }
}
