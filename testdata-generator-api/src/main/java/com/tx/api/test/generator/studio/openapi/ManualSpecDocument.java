package com.tx.api.test.generator.studio.openapi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tx.api.test.generator.studio.dto.ManualOperationRequest;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ManualSpecDocument {

    public static final String MANUAL_EXTENSION = "x-testdata-manual";
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^{}]+)}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private ManualSpecDocument() {
    }

    public static boolean isManual(OpenAPI openApi) {
        if (openApi == null || openApi.getInfo() == null || openApi.getInfo().getExtensions() == null) {
            return false;
        }
        Object flag = openApi.getInfo().getExtensions().get(MANUAL_EXTENSION);
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    public static boolean isManualContent(String content) {
        if (StringUtils.isBlank(content)) {
            return false;
        }
        try {
            JsonElement root = JsonParser.parseString(content);
            if (!root.isJsonObject()) {
                return false;
            }
            JsonObject info = root.getAsJsonObject().getAsJsonObject("info");
            return info != null && info.has(MANUAL_EXTENSION) && info.get(MANUAL_EXTENSION).getAsBoolean();
        } catch (RuntimeException ignored) {
            return content.contains("\"" + MANUAL_EXTENSION + "\"");
        }
    }

    public static String emptyCatalog(String title, String baseUrl) {
        JsonObject root = new JsonObject();
        root.addProperty("openapi", "3.0.3");
        JsonObject info = new JsonObject();
        info.addProperty("title", StringUtils.defaultIfBlank(title, "REST APIs").trim());
        info.addProperty("version", "1.0.0");
        info.addProperty("description", "REST APIs added in Studio without a Swagger spec");
        info.addProperty(MANUAL_EXTENSION, true);
        root.add("info", info);
        JsonArray servers = new JsonArray();
        JsonObject server = new JsonObject();
        server.addProperty("url", StringUtils.defaultIfBlank(baseUrl, "http://localhost:8080").trim());
        servers.add(server);
        root.add("servers", servers);
        root.add("paths", new JsonObject());
        return GSON.toJson(root);
    }

    public static String addOperation(String content, ManualOperationRequest request) {
        JsonObject root = parseObject(content);
        JsonObject paths = root.has("paths") && root.get("paths").isJsonObject()
                ? root.getAsJsonObject("paths")
                : new JsonObject();
        root.add("paths", paths);

        String method = requireMethod(request == null ? null : request.method());
        String path = normalizePath(request == null ? null : request.path());
        String methodKey = method.toLowerCase(Locale.ROOT);
        JsonObject pathItem = paths.has(path) && paths.get(path).isJsonObject()
                ? paths.getAsJsonObject(path)
                : new JsonObject();
        if (pathItem.has(methodKey)) {
            throw new IllegalArgumentException(method + " " + path + " is already in this catalog");
        }

        JsonObject operation = new JsonObject();
        String summary = StringUtils.defaultIfBlank(request.summary(), method + " " + path).trim();
        operation.addProperty("summary", summary);
        operation.addProperty("operationId", operationId(method, path));
        if (StringUtils.isNotBlank(request.description())) {
            operation.addProperty("description", request.description().trim());
        }
        String tag = StringUtils.defaultIfBlank(request.tag(), "General").trim();
        JsonArray tags = new JsonArray();
        tags.add(tag);
        operation.add("tags", tags);

        JsonArray parameters = new JsonArray();
        for (String name : pathParamNames(path)) {
            parameters.add(parameter(name, "path", true));
        }
        for (String name : namedList(request == null ? null : request.queryNames())) {
            parameters.add(parameter(name, "query", false));
        }
        for (String name : namedList(request == null ? null : request.headerNames())) {
            parameters.add(parameter(name, "header", false));
        }
        if (parameters.size() > 0) {
            operation.add("parameters", parameters);
        }

        boolean hasBody = request != null && Boolean.TRUE.equals(request.hasRequestBody());
        if (hasBody || StringUtils.isNotBlank(request == null ? null : request.requestExample())) {
            operation.add("requestBody", requestBody(request == null ? null : request.requestExample()));
        }

        int status = request == null || request.statusCode() == null ? defaultStatus(method) : request.statusCode();
        JsonObject responses = new JsonObject();
        JsonObject response = new JsonObject();
        response.addProperty("description", "OK");
        responses.add(String.valueOf(status), response);
        operation.add("responses", responses);

        pathItem.add(methodKey, operation);
        paths.add(path, pathItem);
        return GSON.toJson(root);
    }

    public static String removeOperation(String content, String method, String path) {
        JsonObject root = parseObject(content);
        JsonObject paths = root.getAsJsonObject("paths");
        if (paths == null || !paths.has(path)) {
            throw new java.util.NoSuchElementException("Operation not found");
        }
        JsonObject pathItem = paths.getAsJsonObject(path);
        String methodKey = requireMethod(method).toLowerCase(Locale.ROOT);
        if (!pathItem.has(methodKey)) {
            throw new java.util.NoSuchElementException("Operation not found");
        }
        pathItem.remove(methodKey);
        if (pathItem.entrySet().isEmpty()) {
            paths.remove(path);
        }
        return GSON.toJson(root);
    }

    private static JsonObject requestBody(String exampleJson) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", true);
        JsonObject media = new JsonObject();
        media.add("schema", schema);
        if (StringUtils.isNotBlank(exampleJson)) {
            try {
                media.add("example", JsonParser.parseString(exampleJson.trim()));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Request example must be valid JSON");
            }
        }
        JsonObject content = new JsonObject();
        content.add("application/json", media);
        JsonObject body = new JsonObject();
        body.addProperty("required", false);
        body.add("content", content);
        return body;
    }

    private static JsonObject parameter(String name, String in, boolean required) {
        JsonObject parameter = new JsonObject();
        parameter.addProperty("name", name);
        parameter.addProperty("in", in);
        parameter.addProperty("required", required);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        parameter.add("schema", schema);
        return parameter;
    }

    private static List<String> pathParamNames(String path) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PATH_PARAM.matcher(path);
        while (matcher.find()) {
            names.add(matcher.group(1).trim());
        }
        return names;
    }

    private static List<String> namedList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                names.add(value.trim());
            }
        }
        return List.copyOf(names);
    }

    private static String requireMethod(String method) {
        String value = StringUtils.upperCase(StringUtils.trimToNull(method), Locale.ROOT);
        if (value == null || !METHODS.contains(value)) {
            throw new IllegalArgumentException("HTTP method must be GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS");
        }
        return value;
    }

    public static String normalizePath(String path) {
        String value = StringUtils.trimToNull(path);
        if (value == null) {
            throw new IllegalArgumentException("Path is required, e.g. /users/{id}");
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        return value.replaceAll("/{2,}", "/");
    }

    private static String operationId(String method, String path) {
        return (method.toLowerCase(Locale.ROOT) + path).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static int defaultStatus(String method) {
        return "POST".equals(method) ? 201 : 200;
    }

    private static JsonObject parseObject(String content) {
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("REST catalog is missing");
        }
        JsonElement root = JsonParser.parseString(content);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("REST catalog is not valid JSON");
        }
        return root.getAsJsonObject();
    }
}
