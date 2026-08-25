package com.tx.api.test.generator.openapi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.tx.api.test.generator.config.GeneratorOptions;
import com.tx.api.test.generator.model.TestDataRow;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class OpenApiTestDataMapper {

    private static final String NA = "NA";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final GeneratorOptions options;
    private final Path payloadDirectory;
    private final Path schemaDirectory;
    private final Pattern includePattern;
    private final Pattern excludePattern;
    private SchemaResolver schemaResolver;

    public OpenApiTestDataMapper(GeneratorOptions options, Path payloadDirectory, Path schemaDirectory) {
        this.options = options;
        this.payloadDirectory = payloadDirectory;
        this.schemaDirectory = schemaDirectory;
        this.includePattern = compilePattern(options.getIncludePattern());
        this.excludePattern = compilePattern(options.getExcludePattern());
    }

    public List<TestDataRow> map(OpenAPI openApi) {
        this.schemaResolver = new SchemaResolver(openApi);
        List<TestDataRow> rows = new ArrayList<>();
        String baseUrl = resolveBaseUrl(openApi);
        int testCaseNo = 1;

        if (openApi.getPaths() == null) {
            return rows;
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
                if (operation == null || !matchesFilters(rawPath, operation)) {
                    continue;
                }

                rows.add(buildRow(testCaseNo++, baseUrl, rawPath, httpMethod, operation));
            }
        }

        return rows;
    }

    private TestDataRow buildRow(int testCaseNo, String baseUrl, String rawPath,
                                 PathItem.HttpMethod httpMethod, Operation operation) {
        List<Parameter> parameters = mergeParameters(operation);
        Map<String, String> queryParams = extractQueryParams(parameters);
        Map<String, String> headerParams = extractHeaderParams(parameters);
        String endpoint = buildEndpoint(rawPath, parameters);
        String operationKey = resolveOperationKey(httpMethod, rawPath, operation);
        RequestBodyInfo requestBodyInfo = buildRequestBody(operation, operationKey);
        ResponseInfo responseInfo = buildResponseInfo(operation, operationKey, httpMethod);

        return TestDataRow.builder()
                .testCaseNo(testCaseNo)
                .serviceName(buildServiceName(httpMethod, rawPath, operation))
                .serviceBaseUrl(StringUtils.defaultIfBlank(options.getBaseUrlOverride(), baseUrl))
                .requestType(httpMethod.name())
                .endpoint(endpoint)
                .contentType(requestBodyInfo.contentType())
                .queryParamKey(joinPipe(queryParams.keySet()))
                .queryParamValue(joinPipe(queryParams.values()))
                .headerName(joinPipe(headerParams.keySet()))
                .headerValue(joinPipe(headerParams.values()))
                .requestBody(requestBodyInfo.bodyReference())
                .statusCode(responseInfo.statusCode())
                .responseType(responseInfo.responseType())
                .expectedResponse(NA)
                .enabled(options.getEnabled())
                .testGroup(resolveTestGroup(operation))
                .jsonPathAssertions(responseInfo.jsonPathAssertions())
                .jsonSchemaPath(responseInfo.jsonSchemaPath())
                .maxResponseTimeMs(options.getMaxResponseTimeMs())
                .build();
    }

    private String resolveBaseUrl(OpenAPI openApi) {
        if (StringUtils.isNotBlank(options.getBaseUrlOverride())) {
            return options.getBaseUrlOverride().trim();
        }
        if (openApi.getServers() != null && !openApi.getServers().isEmpty()) {
            Server server = openApi.getServers().get(0);
            if (server != null && StringUtils.isNotBlank(server.getUrl())) {
                return trimTrailingSlash(server.getUrl().trim());
            }
        }
        return "http://localhost:8080";
    }

    private List<Parameter> mergeParameters(Operation operation) {
        List<Parameter> parameters = new ArrayList<>();
        if (operation.getParameters() != null) {
            parameters.addAll(operation.getParameters());
        }
        return parameters;
    }

    private Map<String, String> extractQueryParams(List<Parameter> parameters) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        for (Parameter parameter : parameters) {
            if (parameter == null || !"query".equalsIgnoreCase(parameter.getIn())) {
                continue;
            }
            queryParams.put(parameter.getName(), sampleParameterValue(parameter));
        }
        return queryParams;
    }

    private Map<String, String> extractHeaderParams(List<Parameter> parameters) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Parameter parameter : parameters) {
            if (parameter == null || !"header".equalsIgnoreCase(parameter.getIn())) {
                continue;
            }
            if (isAuthHeader(parameter.getName())) {
                continue;
            }
            headers.put(parameter.getName(), sampleParameterValue(parameter));
        }
        return headers;
    }

    private String sampleParameterValue(Parameter parameter) {
        Schema<?> resolvedSchema = parameter.getSchema() != null
                ? schemaResolver.resolve(parameter.getSchema())
                : null;
        return SchemaSampleGenerator.sampleParameterValue(parameter, resolvedSchema);
    }

    private String buildEndpoint(String rawPath, List<Parameter> parameters) {
        String endpoint = rawPath;
        for (Parameter parameter : parameters) {
            if (parameter == null || !"path".equalsIgnoreCase(parameter.getIn())) {
                continue;
            }
            String sample = sampleParameterValue(parameter);
            endpoint = endpoint.replace("{" + parameter.getName() + "}", sample);
        }
        return endpoint;
    }

    private RequestBodyInfo buildRequestBody(Operation operation, String operationKey) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return new RequestBodyInfo(NA, NA);
        }

        MediaType mediaType = selectJsonMediaType(operation.getRequestBody().getContent());
        if (mediaType == null || mediaType.getSchema() == null) {
            return new RequestBodyInfo(NA, NA);
        }

        Schema<?> resolvedSchema = schemaResolver.resolve(mediaType.getSchema());
        JsonElement sample = SchemaSampleGenerator.generateSample(resolvedSchema);
        if (sample == null || sample.isJsonNull()) {
            sample = new com.google.gson.JsonObject();
        }

        String fileName = sanitizeFileName(operationKey) + ".json";
        Path payloadFile = payloadDirectory.resolve(fileName);
        writeJsonFile(payloadFile, sample);

        String reference = "file:" + normalizePathPrefix(options.getPayloadPathPrefix()) + "/" + fileName;
        return new RequestBodyInfo("JSON", reference);
    }

    private ResponseInfo buildResponseInfo(Operation operation, String operationKey, PathItem.HttpMethod httpMethod) {
        SuccessResponse successResponse = findSuccessResponse(operation.getResponses());
        if (successResponse == null) {
            return new ResponseInfo(defaultStatusCode(httpMethod), "NONE", NA, NA);
        }

        int statusCode = successResponse.statusCode();
        ApiResponse response = successResponse.response();
        if (response.getContent() == null) {
            return new ResponseInfo(statusCode, "NONE", NA, NA);
        }

        MediaType mediaType = selectJsonMediaType(response.getContent());
        if (mediaType == null || mediaType.getSchema() == null) {
            return new ResponseInfo(statusCode, "NONE", NA, NA);
        }

        Schema<?> resolvedSchema = schemaResolver.resolve(mediaType.getSchema());
        String schemaFileName = sanitizeFileName(operationKey) + ".schema.json";
        Path schemaFile = schemaDirectory.resolve(schemaFileName);
        JsonElement schemaJson = OpenApiSchemaConverter.toJsonSchema(resolvedSchema);
        writeJsonFile(schemaFile, schemaJson);

        String schemaReference = "file:" + normalizePathPrefix(options.getSchemaPathPrefix()) + "/" + schemaFileName;
        String jsonPathAssertions = buildJsonPathAssertions(resolvedSchema);
        String responseType = OpenApiSchemaConverter.isJsonStructuredSchema(resolvedSchema) ? "JSON" : "NONE";

        return new ResponseInfo(statusCode, responseType, jsonPathAssertions, schemaReference);
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
        if (StringUtils.isBlank(statusCode)) {
            return 200;
        }
        String normalized = statusCode.trim();
        if ("default".equalsIgnoreCase(normalized)) {
            return 200;
        }
        try {
            return Integer.parseInt(normalized);
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

    private String buildJsonPathAssertions(Schema schema) {
        if (schema == null) {
            return NA;
        }

        Map<String, String> assertions = SchemaSampleGenerator.buildJsonPathAssertions(schema);
        if (assertions.isEmpty()) {
            return NA;
        }

        return assertions.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "|" + right)
                .orElse(NA);
    }

    private MediaType selectJsonMediaType(Content content) {
        if (content.containsKey("application/json")) {
            return content.get("application/json");
        }
        if (content.containsKey("application/*+json")) {
            return content.get("application/*+json");
        }
        return content.isEmpty() ? null : content.values().iterator().next();
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
        if (operation.getTags() != null && !operation.getTags().isEmpty()) {
            return operation.getTags().get(0);
        }
        if (StringUtils.isNotBlank(options.getDefaultTestGroup())) {
            return options.getDefaultTestGroup().trim();
        }
        return "generated";
    }

    private String resolveOperationKey(PathItem.HttpMethod httpMethod, String rawPath, Operation operation) {
        if (StringUtils.isNotBlank(operation.getOperationId())) {
            return operation.getOperationId().trim();
        }
        return httpMethod.name().toLowerCase(Locale.ROOT) + rawPath.replace("/", "_").replace("{", "").replace("}", "");
    }

    private boolean matchesFilters(String path, Operation operation) {
        String candidate = path + " " + StringUtils.defaultString(operation.getOperationId()) + " "
                + StringUtils.defaultString(operation.getSummary());
        if (includePattern != null && !includePattern.matcher(candidate).find()) {
            return false;
        }
        return excludePattern == null || !excludePattern.matcher(candidate).find();
    }

    private Pattern compilePattern(String expression) {
        if (StringUtils.isBlank(expression)) {
            return null;
        }
        return Pattern.compile(expression.trim());
    }

    private void writeJsonFile(Path file, JsonElement jsonElement) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(jsonElement), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write JSON file: " + file, exception);
        }
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String normalizePathPrefix(String prefix) {
        return prefix.replace('\\', '/').replaceAll("/+$", "");
    }

    private String joinPipe(Iterable<String> values) {
        List<String> items = new ArrayList<>();
        values.forEach(value -> items.add(value));
        return items.isEmpty() ? NA : String.join("|", items);
    }

    private String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private boolean isAuthHeader(String headerName) {
        return headerName != null && headerName.equalsIgnoreCase("Authorization");
    }

    private String humanize(String value) {
        return value.replaceAll("[_\\-]+", " ").trim();
    }

    private record RequestBodyInfo(String contentType, String bodyReference) {
    }

    private record ResponseInfo(int statusCode, String responseType, String jsonPathAssertions, String jsonSchemaPath) {
    }

    private record SuccessResponse(int statusCode, ApiResponse response) {
    }
}
