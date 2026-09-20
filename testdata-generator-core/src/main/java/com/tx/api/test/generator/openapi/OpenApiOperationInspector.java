package com.tx.api.test.generator.openapi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.tx.api.test.generator.catalog.OperationAuth;
import com.tx.api.test.generator.catalog.OperationDefaults;
import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.catalog.OperationSummary;
import com.tx.api.test.generator.catalog.ParameterField;
import com.tx.api.test.generator.catalog.RequestBodySchema;
import com.tx.api.test.generator.catalog.ResponseSchema;
import com.tx.api.test.generator.catalog.SpecServer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class OpenApiOperationInspector {

    private static final String NA = "NA";
    private static final Gson GSON = new GsonBuilder().create();

    private final String baseUrlOverride;
    private SchemaResolver schemaResolver;

    public OpenApiOperationInspector(String baseUrlOverride) {
        this.baseUrlOverride = baseUrlOverride;
    }

    public List<OperationPage> inspect(OpenAPI openApi) {
        this.schemaResolver = new SchemaResolver(openApi);
        List<OperationPage> pages = new ArrayList<>();
        Set<String> usedKeys = new LinkedHashSet<>();
        List<SpecServer> servers = OpenApiServerInspector.inspect(openApi);
        String baseUrl = resolveBaseUrl(openApi);
        if (servers.isEmpty() && StringUtils.isNotBlank(baseUrl)) {
            servers = List.of(new SpecServer(baseUrl, "", baseUrl, Map.of()));
        }

        if (openApi.getPaths() == null) {
            return pages;
        }

        for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
            String rawPath = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();
            if (pathItem == null) {
                continue;
            }
            for (Map.Entry<PathItem.HttpMethod, Operation> operationEntry : pathItem.readOperationsMap().entrySet()) {
                PathItem.HttpMethod httpMethod = operationEntry.getKey();
                Operation operation = operationEntry.getValue();
                if (operation == null) {
                    continue;
                }
                pages.add(buildPage(openApi, servers, baseUrl, rawPath, pathItem, httpMethod, operation, usedKeys));
            }
        }
        return pages;
    }

    public static String specTitle(OpenAPI openApi) {
        Info info = openApi.getInfo();
        if (info != null && StringUtils.isNotBlank(info.getTitle())) {
            return info.getTitle().trim();
        }
        return "OpenAPI Spec";
    }

    public static String specVersion(OpenAPI openApi) {
        Info info = openApi.getInfo();
        if (info != null && StringUtils.isNotBlank(info.getVersion())) {
            return info.getVersion().trim();
        }
        return "";
    }

    public static String specDescription(OpenAPI openApi) {
        Info info = openApi.getInfo();
        if (info != null && StringUtils.isNotBlank(info.getDescription())) {
            return info.getDescription().trim();
        }
        return "";
    }

    public String resolveBaseUrl(OpenAPI openApi) {
        if (StringUtils.isNotBlank(baseUrlOverride)) {
            return trimTrailingSlash(baseUrlOverride.trim());
        }
        if (openApi.getServers() != null && !openApi.getServers().isEmpty()) {
            List<SpecServer> servers = OpenApiServerInspector.inspect(openApi);
            if (!servers.isEmpty() && StringUtils.isNotBlank(servers.get(0).resolvedUrl())) {
                return servers.get(0).resolvedUrl();
            }
            Server server = openApi.getServers().get(0);
            if (server != null && StringUtils.isNotBlank(server.getUrl())) {
                return trimTrailingSlash(OpenApiServerInspector.resolve(server.getUrl().trim(), Map.of()));
            }
        }
        return "http://localhost:8080";
    }

    private OperationPage buildPage(OpenAPI openApi, List<SpecServer> servers, String baseUrl, String rawPath,
                                    PathItem pathItem, PathItem.HttpMethod httpMethod, Operation operation,
                                    Set<String> usedKeys) {
        String key = uniqueKey(httpMethod, rawPath, operation, usedKeys);
        List<Parameter> parameters = mergeParameters(pathItem, operation);
        List<ParameterField> parameterFields = parameters.stream()
                .filter(parameter -> parameter != null && StringUtils.isNotBlank(parameter.getName()))
                .map(this::toParameterField)
                .toList();

        RequestBodySchema requestBody = buildRequestBody(operation);
        ResponseSchema response = buildResponse(operation, httpMethod);
        List<Integer> statusCodes = collectStatusCodes(operation, httpMethod, response);
        String testName = buildServiceName(httpMethod, rawPath, operation);
        String testGroup = resolveTestGroup(operation);
        String mappedContentType = requestBody == null ? NA : requestBody.mappedContentType();

        OperationDefaults defaults = new OperationDefaults(
                testName,
                testGroup,
                mappedContentType,
                response == null ? "NONE" : response.responseType(),
                "Y",
                3000L,
                baseUrl
        );

        return new OperationPage(
                key,
                httpMethod.name(),
                rawPath,
                rawPath,
                testName,
                StringUtils.defaultString(operation.getDescription()),
                testGroup,
                parameterFields,
                requestBody,
                response,
                statusCodes,
                defaults,
                requestContentTypes(operation),
                OpenApiSecurityInspector.inspect(openApi, operation),
                servers,
                environmentNames(operation)
        );
    }

    public static OperationSummary toSummary(OperationPage page) {
        return new OperationSummary(
                page.key(),
                page.method(),
                page.path(),
                page.summary(),
                page.tag(),
                page.requestBody() != null,
                page.response() == null ? 200 : page.response().statusCode(),
                page.environmentNames()
        );
    }

    private ParameterField toParameterField(Parameter parameter) {
        Schema<?> resolvedSchema = parameter.getSchema() != null
                ? schemaResolver.resolve(parameter.getSchema())
                : null;
        Map<String, Object> schemaMap = toMap(OpenApiSchemaConverter.toJsonSchema(resolvedSchema));
        String sample = SchemaSampleGenerator.sampleParameterValue(parameter, resolvedSchema);
        boolean required = Boolean.TRUE.equals(parameter.getRequired())
                || "path".equalsIgnoreCase(parameter.getIn());
        return new ParameterField(
                parameter.getName(),
                StringUtils.defaultIfBlank(parameter.getIn(), "query"),
                required,
                StringUtils.defaultString(parameter.getDescription()),
                schemaMap,
                sample,
                parameter.getStyle() == null ? null : parameter.getStyle().toString(),
                parameter.getExplode()
        );
    }

    private RequestBodySchema buildRequestBody(Operation operation) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return null;
        }
        Content content = operation.getRequestBody().getContent();
        Map.Entry<String, MediaType> selected = selectMediaType(content);
        if (selected == null || selected.getValue() == null || selected.getValue().getSchema() == null) {
            return null;
        }
        Schema<?> resolvedSchema = schemaResolver.resolve(selected.getValue().getSchema());
        JsonElement schemaJson = OpenApiSchemaConverter.toJsonSchema(resolvedSchema);
        JsonElement sample = SchemaSampleGenerator.generateSample(resolvedSchema);
        String mediaType = selected.getKey();
        return new RequestBodySchema(
                Boolean.TRUE.equals(operation.getRequestBody().getRequired()),
                mediaType,
                mapContentType(mediaType),
                toMap(schemaJson),
                toValue(sample)
        );
    }

    private ResponseSchema buildResponse(Operation operation, PathItem.HttpMethod httpMethod) {
        SuccessResponse successResponse = findSuccessResponse(operation.getResponses());
        if (successResponse == null) {
            return new ResponseSchema(defaultStatusCode(httpMethod), NA, "NONE", Map.of(), null, NA);
        }
        int statusCode = successResponse.statusCode();
        ApiResponse response = successResponse.response();
        if (response == null || response.getContent() == null) {
            return new ResponseSchema(statusCode, NA, "NONE", Map.of(), null, NA);
        }
        Map.Entry<String, MediaType> selected = selectMediaType(response.getContent());
        if (selected == null || selected.getValue() == null || selected.getValue().getSchema() == null) {
            return new ResponseSchema(statusCode, NA, "NONE", Map.of(), null, NA);
        }
        Schema<?> resolvedSchema = schemaResolver.resolve(selected.getValue().getSchema());
        JsonElement schemaJson = OpenApiSchemaConverter.toJsonSchema(resolvedSchema);
        JsonElement sample = SchemaSampleGenerator.generateSample(resolvedSchema);
        String assertions = buildJsonPathAssertions(resolvedSchema);
        String responseType = responseType(selected.getKey(), resolvedSchema);
        return new ResponseSchema(
                statusCode,
                selected.getKey(),
                responseType,
                toMap(schemaJson),
                toValue(sample),
                assertions
        );
    }

    private String buildJsonPathAssertions(Schema<?> schema) {
        if (schema == null) {
            return NA;
        }
        Map<String, String> assertions = SchemaSampleGenerator.buildJsonPathAssertions(schema);
        if (assertions.isEmpty()) {
            return NA;
        }
        return String.join("|", assertions.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList());
    }

    private List<Integer> collectStatusCodes(Operation operation, PathItem.HttpMethod httpMethod,
                                             ResponseSchema primary) {
        LinkedHashSet<Integer> codes = new LinkedHashSet<>();
        if (operation.getResponses() != null) {
            for (String key : operation.getResponses().keySet()) {
                Integer parsed = numericStatusCode(key);
                if (parsed != null) {
                    codes.add(parsed);
                }
            }
        }
        if (primary != null) {
            codes.add(primary.statusCode());
        }
        if (codes.isEmpty()) {
            codes.add(defaultStatusCode(httpMethod));
        }
        return List.copyOf(codes);
    }

    private Integer numericStatusCode(String statusCode) {
        if (StringUtils.isBlank(statusCode) || "default".equalsIgnoreCase(statusCode.trim())) {
            return null;
        }
        try {
            return Integer.parseInt(statusCode.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private SuccessResponse findSuccessResponse(Map<String, ApiResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
            if (entry.getKey() != null && entry.getKey().startsWith("2")) {
                return new SuccessResponse(parseStatusCode(entry.getKey()), entry.getValue());
            }
        }
        Map.Entry<String, ApiResponse> firstEntry = responses.entrySet().iterator().next();
        return new SuccessResponse(parseStatusCode(firstEntry.getKey()), firstEntry.getValue());
    }

    private int parseStatusCode(String statusCode) {
        if (StringUtils.isBlank(statusCode) || "default".equalsIgnoreCase(statusCode.trim())) {
            return 200;
        }
        try {
            return Integer.parseInt(statusCode.trim());
        } catch (NumberFormatException exception) {
            return 200;
        }
    }

    private int defaultStatusCode(PathItem.HttpMethod httpMethod) {
        return switch (httpMethod) {
            case POST -> 201;
            case DELETE -> 204;
            default -> 200;
        };
    }

    private Map.Entry<String, MediaType> selectMediaType(Content content) {
        if (content.containsKey("application/json")) {
            return Map.entry("application/json", content.get("application/json"));
        }
        for (String key : content.keySet()) {
            if (key != null && key.toLowerCase(Locale.ROOT).contains("json")) {
                return Map.entry(key, content.get(key));
            }
        }
        if (content.containsKey("multipart/form-data")) {
            return Map.entry("multipart/form-data", content.get("multipart/form-data"));
        }
        if (content.containsKey("application/x-www-form-urlencoded")) {
            return Map.entry("application/x-www-form-urlencoded", content.get("application/x-www-form-urlencoded"));
        }
        if (content.containsKey("application/xml") || content.containsKey("text/xml")) {
            String key = content.containsKey("application/xml") ? "application/xml" : "text/xml";
            return Map.entry(key, content.get(key));
        }
        return content.isEmpty() ? null : content.entrySet().iterator().next();
    }

    private List<String> requestContentTypes(Operation operation) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return List.of();
        }
        return new ArrayList<>(operation.getRequestBody().getContent().keySet());
    }

    private String responseType(String mediaType, Schema<?> schema) {
        String mapped = mapContentType(mediaType);
        if ("FILE".equals(mapped) || OpenApiSchemaConverter.isBinarySchema(schema)) {
            return "FILE";
        }
        if ("JSON".equals(mapped) || OpenApiSchemaConverter.isJsonStructuredSchema(schema)) {
            return "JSON";
        }
        if ("XML".equals(mapped) || "TEXT".equals(mapped)) {
            return "STRING";
        }
        return "NONE";
    }

    private String mapContentType(String mediaType) {
        if (mediaType == null) {
            return NA;
        }
        String normalized = mediaType.toLowerCase(Locale.ROOT);
        if (normalized.contains("json")) {
            return "JSON";
        }
        if (normalized.contains("multipart")) {
            return "MULTIPART";
        }
        if (normalized.contains("urlencoded") || normalized.contains("form-urlencoded")) {
            return "FORM";
        }
        if (isXmlMediaType(normalized)) {
            return "XML";
        }
        if (normalized.contains("text/plain") || (normalized.startsWith("text/") && !normalized.contains("xml"))) {
            return "TEXT";
        }
        if (normalized.contains("octet-stream") || normalized.startsWith("image/")
                || normalized.startsWith("audio/") || normalized.startsWith("video/")
                || normalized.contains("pdf") || normalized.contains("zip")) {
            return "FILE";
        }
        return "JSON";
    }

    static boolean isXmlMediaType(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String value = mediaType.trim().toLowerCase(Locale.ROOT).split(";")[0].trim();
        return "xml".equals(value)
                || "application/xml".equals(value)
                || "text/xml".equals(value)
                || value.endsWith("+xml");
    }

    private List<Parameter> mergeParameters(PathItem pathItem, Operation operation) {
        Map<String, Parameter> merged = new LinkedHashMap<>();
        addParameters(merged, pathItem.getParameters());
        addParameters(merged, operation.getParameters());
        return new ArrayList<>(merged.values());
    }

    private void addParameters(Map<String, Parameter> merged, List<Parameter> parameters) {
        if (parameters == null) {
            return;
        }
        for (Parameter parameter : parameters) {
            if (parameter == null || StringUtils.isBlank(parameter.getName())) {
                continue;
            }
            merged.put(StringUtils.defaultString(parameter.getIn()) + ":" + parameter.getName(), parameter);
        }
    }

    private String uniqueKey(PathItem.HttpMethod httpMethod, String rawPath, Operation operation, Set<String> usedKeys) {
        String base = resolveOperationKey(httpMethod, rawPath, operation);
        String key = base;
        int suffix = 2;
        while (!usedKeys.add(key)) {
            key = base + "_" + suffix++;
        }
        return key;
    }

    private String resolveOperationKey(PathItem.HttpMethod httpMethod, String rawPath, Operation operation) {
        if (StringUtils.isNotBlank(operation.getOperationId())) {
            return sanitizeKey(operation.getOperationId().trim());
        }
        return sanitizeKey(httpMethod.name().toLowerCase(Locale.ROOT) + rawPath);
    }

    private String sanitizeKey(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String buildServiceName(PathItem.HttpMethod httpMethod, String rawPath, Operation operation) {
        if (StringUtils.isNotBlank(operation.getSummary())) {
            return operation.getSummary().trim();
        }
        if (StringUtils.isNotBlank(operation.getOperationId())) {
            return humanize(operation.getOperationId());
        }
        return httpMethod.name() + " " + rawPath;
    }

    private String resolveTestGroup(Operation operation) {
        if (operation.getTags() != null && !operation.getTags().isEmpty()
                && StringUtils.isNotBlank(operation.getTags().get(0))) {
            return operation.getTags().get(0);
        }
        return "generated";
    }

    private String humanize(String value) {
        return value.replaceAll("[_\\-]+", " ").trim();
    }

    private String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Map.of();
        }
        if (element.isJsonObject()) {
            Map<String, Object> map = GSON.fromJson(element, Map.class);
            return map == null ? Map.of() : map;
        }
        return Map.of("type", "object");
    }

    private Object toValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return GSON.fromJson(element, Object.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> environmentNames(Operation operation) {
        if (operation == null || operation.getExtensions() == null) {
            return null;
        }
        Object value = operation.getExtensions().get("x-testdata-environments");
        if (value == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && StringUtils.isNotBlank(item.toString())) {
                    names.add(item.toString().trim());
                }
            }
        } else if (StringUtils.isNotBlank(value.toString())) {
            names.add(value.toString().trim());
        }
        return names.isEmpty() ? null : List.copyOf(names);
    }

    private record SuccessResponse(int statusCode, ApiResponse response) {
    }
}
