package com.tx.api.test.generator.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tx.api.test.generator.TestdataGenerator;
import com.tx.api.test.generator.catalog.OperationAuth;
import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.catalog.ResponseSchema;
import com.tx.api.test.generator.config.GeneratorOptions;
import com.tx.api.test.generator.generate.GeneratedTestCase;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Offline generator: turns an OpenAPI spec into a Studio collection ({@code testdata.studio.collection} v1) and a
 * matching environment file ({@code testdata.studio.environment} v1). api-automation runs the pair directly, and
 * Studio can import the collection (Export → Import collection) to keep editing it.
 */
public final class CollectionGenerator {

    public static final String COLLECTION_FORMAT = "testdata.studio.collection";
    public static final String ENVIRONMENT_FORMAT = "testdata.studio.environment";
    public static final int VERSION = 1;
    private static final String BASE_URL_PLACEHOLDER = "{{baseUrl}}";
    private static final String NA = "NA";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Files written by {@link #generate(GeneratorOptions)}. */
    public record Result(Path collectionFile, Path environmentFile, int caseCount, int operationCount) {
    }

    /** Collection and environment documents built in memory (as JSON-ready maps). */
    public record Documents(Map<String, Object> collection, Map<String, Object> environment, int operationCount) {
    }

    public Result generate(GeneratorOptions options) {
        OpenAPI openApi = TestdataGenerator.loadSpec(options.getSwaggerLocation());
        Documents documents = build(openApi, options);
        String specName = String.valueOf(((Map<?, ?>) documents.collection().get("spec")).get("name"));
        Path collectionFile = collectionPath(options, specName);
        Path environmentFile = environmentPath(options, collectionFile, specName);
        write(collectionFile, documents.collection());
        write(environmentFile, documents.environment());
        int cases = ((List<?>) documents.collection().get("cases")).size();
        return new Result(collectionFile, environmentFile, cases, documents.operationCount());
    }

    public Documents build(OpenAPI openApi, GeneratorOptions options) {
        String override = StringUtils.trimToNull(options.getBaseUrlOverride());
        String baseUrl = StringUtils.defaultIfBlank(TestdataGenerator.resolveBaseUrl(openApi, override), "");
        Pattern include = compile(options.getIncludePattern());
        Pattern exclude = compile(options.getExcludePattern());
        List<OperationPage> operations = new ArrayList<>();
        for (OperationPage operation : TestdataGenerator.inspect(openApi, override)) {
            if (matches(operation, include, exclude)) {
                operations.add(operation);
            }
        }
        if (operations.isEmpty()) {
            throw new IllegalStateException("No operations matched the spec and the --include/--exclude filters.");
        }

        String title = StringUtils.defaultIfBlank(TestdataGenerator.specTitle(openApi), "api");
        String specName = slug(title, "api");
        String planName = StringUtils.defaultIfBlank(StringUtils.trimToNull(options.getPlanName()), "Default");
        String environmentName = StringUtils.defaultIfBlank(StringUtils.trimToNull(options.getEnvironmentName()),
                "Default");

        List<Map<String, Object>> cases = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (OperationPage operation : operations) {
            int index = 0;
            for (GeneratedTestCase generated : TestdataGenerator.generateCases(operation)) {
                if (!generated.positive() && !options.isIncludeNegativeCases()) {
                    continue;
                }
                String id = uniqueId(ids, slug(operation.key(), "case") + "-" + (++index));
                cases.add(toCase(id, operation, generated, options));
            }
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", specName);
        spec.put("name", specName);
        spec.put("title", title);
        putIfPresent(spec, "version", TestdataGenerator.specVersion(openApi));
        putIfPresent(spec, "description", TestdataGenerator.specDescription(openApi));
        putIfPresent(spec, "baseUrl", baseUrl);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("id", slug(planName, "Default"));
        plan.put("name", planName);
        plan.put("description", "Generated from " + title + " by testdata-generator-core");

        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("format", COLLECTION_FORMAT);
        collection.put("version", VERSION);
        collection.put("spec", spec);
        collection.put("plan", plan);
        collection.put("cases", cases);

        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("format", ENVIRONMENT_FORMAT);
        environment.put("version", VERSION);
        environment.put("name", environmentName);
        Map<String, String> values = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(baseUrl)) {
            values.put("baseUrl", baseUrl);
        }
        environment.put("values", values);
        environment.put("auth", placeholderAuth(resolveAuth(options.getAuthType(), operations)));
        return new Documents(collection, environment, operations.size());
    }

    private Map<String, Object> toCase(String id, OperationPage operation, GeneratedTestCase generated,
                                       GeneratorOptions options) {
        ResponseSchema response = operation.response();
        boolean jsonResponse = response != null && "JSON".equalsIgnoreCase(response.responseType());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("operationKey", operation.key());
        item.put("testName", generated.testName());
        item.put("method", operation.method());
        item.put("path", operation.rawPath());
        item.put("endpoint", endpoint(operation.rawPath(), generated.pathParams()));
        item.put("baseUrl", BASE_URL_PLACEHOLDER);
        item.put("contentType", StringUtils.defaultIfBlank(generated.contentType(), NA));
        item.put("pathParams", nonNull(generated.pathParams()));
        item.put("queryParams", nonNull(generated.queryParams()));
        item.put("headers", nonNull(generated.headers()));
        item.put("cookies", nonNull(generated.cookieParams()));
        if (generated.requestBody() != null) {
            item.put("body", wholeNumbers(generated.requestBody()));
        }
        if (generated.positive() && response != null && response.jsonSchema() != null && !response.jsonSchema().isEmpty()) {
            item.put("responseJsonSchema", wholeNumbers(response.jsonSchema()));
        }
        item.put("statusCode", generated.statusCode());
        item.put("responseType", StringUtils.defaultIfBlank(generated.responseType(), "NONE"));
        item.put("expectedResponse", NA);
        item.put("enabled", StringUtils.defaultIfBlank(StringUtils.trimToNull(options.getEnabled()),
                StringUtils.defaultIfBlank(generated.enabled(), "Y")));
        item.put("testGroup", StringUtils.defaultIfBlank(generated.testGroup(),
                StringUtils.defaultIfBlank(options.getDefaultTestGroup(), "generated")));
        item.put("jsonPathAssertions", generated.positive() && jsonResponse
                ? StringUtils.defaultIfBlank(response.jsonPathAssertions(), NA)
                : StringUtils.defaultIfBlank(generated.jsonPathAssertions(), NA));
        item.put("extracts", NA);
        item.put("maxResponseTimeMs", options.getMaxResponseTimeMs() > 0
                ? options.getMaxResponseTimeMs() : generated.maxResponseTimeMs());
        return item;
    }

    /** Schema samples arrive as doubles (2.0); write whole numbers as integers so bodies read naturally. */
    static Object wholeNumbers(Object value) {
        if (value instanceof Double number && !number.isInfinite() && number == Math.rint(number)
                && Math.abs(number) < 9.0e15) {
            return number.longValue();
        }
        if (value instanceof Float number && !number.isInfinite() && number == Math.rint(number)) {
            return number.longValue();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), wholeNumbers(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(wholeNumbers(item)));
            return copy;
        }
        return value;
    }

    static String endpoint(String rawPath, Map<String, String> pathParams) {
        String endpoint = StringUtils.defaultString(rawPath);
        if (pathParams != null) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                endpoint = endpoint.replace("{" + entry.getKey() + "}", StringUtils.defaultString(entry.getValue()));
            }
        }
        return endpoint;
    }

    private static String resolveAuth(String requested, List<OperationPage> operations) {
        if (StringUtils.isNotBlank(requested)) {
            return requested.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        }
        for (OperationPage operation : operations) {
            OperationAuth auth = operation.auth();
            if (auth != null && StringUtils.isNotBlank(auth.type()) && !OperationAuth.NONE.equalsIgnoreCase(auth.type())) {
                return auth.type().toUpperCase(Locale.ROOT);
            }
        }
        return OperationAuth.NONE;
    }

    /** Same {@code ${AUTOMATION_*}} placeholders Studio writes, so no secret ever lands in the file. */
    static Map<String, Object> placeholderAuth(String type) {
        Map<String, Object> auth = new LinkedHashMap<>();
        switch (StringUtils.defaultIfBlank(type, "NONE")) {
            case "BEARER", "TOKEN" -> {
                auth.put("type", "BEARER");
                auth.put("token", "${AUTOMATION_BEARER_TOKEN}");
            }
            case "BASIC", "CREDENTIALS" -> {
                auth.put("type", "BASIC");
                auth.put("username", "${AUTOMATION_USERNAME}");
                auth.put("password", "${AUTOMATION_PASSWORD}");
            }
            case "API_KEY", "APIKEY" -> {
                auth.put("type", "API_KEY");
                auth.put("apiKey", "${AUTOMATION_API_KEY}");
                auth.put("apiKeyName", "X-API-Key");
                auth.put("apiKeyIn", "header");
            }
            case "OAUTH2", "OAUTH" -> {
                auth.put("type", "OAUTH2");
                auth.put("clientId", "${AUTOMATION_PING_IDENTITY_CLIENT_ID}");
                auth.put("clientSecret", "${AUTOMATION_PING_IDENTITY_CLIENT_SECRET}");
                auth.put("tokenUrl", "${AUTOMATION_PING_IDENTITY_TOKEN_URL}");
                auth.put("grantType", "client_credentials");
            }
            case "NONE" -> auth.put("type", "NONE");
            default -> throw new IllegalArgumentException("Unsupported --auth-type: " + type
                    + ". Use none, bearer, basic, api_key, or oauth2.");
        }
        return auth;
    }

    private static boolean matches(OperationPage operation, Pattern include, Pattern exclude) {
        String candidate = StringUtils.defaultString(operation.rawPath()) + " " + StringUtils.defaultString(operation.key())
                + " " + StringUtils.defaultString(operation.summary());
        if (include != null && !include.matcher(candidate).find()) {
            return false;
        }
        return exclude == null || !exclude.matcher(candidate).find();
    }

    private static Pattern compile(String expression) {
        return StringUtils.isBlank(expression) ? null : Pattern.compile(expression.trim());
    }

    private static Path collectionPath(GeneratorOptions options, String specName) {
        if (StringUtils.isNotBlank(options.getOutputFile())) {
            return Path.of(options.getOutputFile().trim());
        }
        String plan = slug(StringUtils.defaultIfBlank(options.getPlanName(), "Default"), "Default");
        return Path.of(specName + "." + plan + ".collection.json");
    }

    private static Path environmentPath(GeneratorOptions options, Path collectionFile, String specName) {
        if (StringUtils.isNotBlank(options.getEnvironmentOutputFile())) {
            return Path.of(options.getEnvironmentOutputFile().trim());
        }
        String environment = slug(StringUtils.defaultIfBlank(options.getEnvironmentName(), "Default"), "Default");
        String name = specName + "." + environment + ".environment.json";
        Path parent = collectionFile.toAbsolutePath().getParent();
        return parent == null ? Path.of(name) : parent.resolve(name);
    }

    private static void write(Path file, Object document) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, GSON.toJson(document) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write " + file, exception);
        }
    }

    public static String toJson(Object document) {
        return GSON.toJson(document);
    }

    /** File- and id-safe name: letters, digits, dot, dash, underscore. */
    static String slug(String value, String fallback) {
        String text = StringUtils.defaultString(value).trim().replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return text.isEmpty() ? fallback : text;
    }

    private static String uniqueId(Set<String> ids, String base) {
        String id = base;
        int suffix = 2;
        while (!ids.add(id)) {
            id = base + "-" + suffix++;
        }
        return id;
    }

    private static Map<String, String> nonNull(Map<String, String> values) {
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.put(key, value);
        }
    }
}
