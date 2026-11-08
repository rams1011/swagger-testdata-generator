package com.tx.api.test.generator.studio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.tx.api.test.generator.studio.collection.EnvironmentOverlay;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data rows of a data-driven case: a JSON array of objects, one run per row. A column such as {@code tier} is the
 * variable {@code {{tier}}} (also {@code {{data:tier}}}) for that row; it ranks above environment variables and
 * below values extracted during the run. A non-text value (number, boolean, object, array) keeps its JSON type where
 * the whole value is a quoted placeholder, {@code "{{tier}}"}.
 * <p>
 * Columns starting with {@code $} change the row's case instead: {@code $name} labels the row in results,
 * {@code $status} sets the expected status, and {@code $body} / {@code $expected} map JSONPaths to values set in
 * the request body or expected response, so a large body stays one template and a row holds only what differs.
 */
public final class DataRows {

    public static final int MAX_ROWS = 1000;
    public static final int MAX_COLUMNS = 100;
    public static final int MAX_CHARS = 2_000_000;
    public static final String DATA_PREFIX = "data:";
    public static final String NAME = "$name";
    public static final String STATUS = "$status";
    public static final String BODY = "$body";
    public static final String EXPECTED = "$expected";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern DOT_CHILD = Pattern.compile("^(\\$.*)\\.([A-Za-z_$][A-Za-z0-9_$-]*)$");
    private static final Pattern BRACKET_CHILD = Pattern.compile("^(\\$.*)\\[['\"]([^'\"]+)['\"]]$");
    private static final Configuration PATHS = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider(JSON))
            .mappingProvider(new JacksonMappingProvider(JSON))
            .build();

    /** One row: its 1-based number, label, variable values and patches. */
    public record Row(int number, String label, Map<String, JsonNode> values, Map<String, JsonNode> body,
                      Map<String, JsonNode> expected, Integer status) {
    }

    private DataRows() {
    }

    /**
     * Validated, compact JSON text for storage, or null for no rows. Accepts a list (REST), JSON text, a Jackson tree
     * or a Gson element (collection file).
     */
    public static String normalize(Object rows) {
        JsonNode tree = tree(rows);
        if (tree == null || tree.isNull() || tree.isArray() && tree.isEmpty()) {
            return null;
        }
        if (!tree.isArray()) {
            throw new IllegalArgumentException("Data rows must be a list of objects, one per run");
        }
        if (tree.size() > MAX_ROWS) {
            throw new IllegalArgumentException("Data has " + tree.size() + " rows; the limit is " + MAX_ROWS);
        }
        ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
        Set<String> columns = new LinkedHashSet<>();
        int number = 0;
        for (JsonNode item : tree) {
            number++;
            if (!item.isObject()) {
                throw new IllegalArgumentException("Data row " + number + " must be an object of column: value");
            }
            ObjectNode row = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String column = StringUtils.trimToEmpty(field.getKey());
                if (column.isEmpty()) {
                    continue;
                }
                row.set(column, checked(number, column, field.getValue()));
                if (!column.startsWith("$")) {
                    columns.add(column);
                }
            }
            normalized.add(row);
        }
        if (columns.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("Data has " + columns.size() + " columns; the limit is " + MAX_COLUMNS);
        }
        String json = normalized.toString();
        if (json.length() > MAX_CHARS) {
            throw new IllegalArgumentException("Data is larger than " + MAX_CHARS
                    + " characters. Keep large bodies in the case and put only the values that change in the rows.");
        }
        return json;
    }

    private static JsonNode checked(int number, String column, JsonNode value) {
        String where = "Data row " + number + ", column '" + column + "'";
        switch (column) {
            case NAME -> {
                return JsonNodeFactory.instance.textNode(value == null || value.isNull() ? "" : text(value));
            }
            case STATUS -> {
                int status = value != null && value.canConvertToInt() ? value.asInt()
                        : value != null && value.isTextual() && value.asText().trim().matches("\\d{3}")
                        ? Integer.parseInt(value.asText().trim()) : -1;
                if (status < 100 || status > 599) {
                    throw new IllegalArgumentException(where + ": the expected status must be a number from 100 to 599");
                }
                return JsonNodeFactory.instance.numberNode(status);
            }
            case BODY, EXPECTED -> {
                JsonNode patches = value != null && value.isTextual() ? parseText(where, value.asText()) : value;
                if (patches == null || !patches.isObject()) {
                    throw new IllegalArgumentException(where
                            + " must be an object of JSONPath: value, such as {\"$.customer.tier\": \"GOLD\"}");
                }
                patches.fieldNames().forEachRemaining(path -> {
                    if (!path.trim().startsWith("$")) {
                        throw new IllegalArgumentException(where + ": '" + path + "' must be a JSONPath starting with $");
                    }
                    try {
                        JsonPath.compile(path.trim());
                    } catch (InvalidPathException exception) {
                        throw new IllegalArgumentException(where + ": '" + path + "' isn't a valid JSONPath");
                    }
                });
                return patches;
            }
            default -> {
                if (column.startsWith("$")) {
                    throw new IllegalArgumentException(where + ": unknown special column. Use $name, $status, $body or "
                            + "$expected, or a variable name");
                }
                if (!KEY.matcher(column).matches()) {
                    throw new IllegalArgumentException(where
                            + ": a column name must start with a letter or _ and use only letters, digits and _");
                }
                return value == null ? JsonNodeFactory.instance.nullNode() : value;
            }
        }
    }

    private static JsonNode parseText(String where, String text) {
        if (StringUtils.isBlank(text)) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(where + " isn't valid JSON");
        }
    }

    private static JsonNode tree(Object rows) {
        if (rows == null) {
            return null;
        }
        try {
            if (rows instanceof JsonNode node) {
                return node;
            }
            if (rows instanceof String text) {
                return StringUtils.isBlank(text) ? null : JSON.readTree(text);
            }
            if (rows instanceof com.google.gson.JsonElement element) {
                return JSON.readTree(element.toString());
            }
            return JSON.valueToTree(rows);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Data rows aren't valid JSON: " + exception.getMessage());
        }
    }

    /** Rows of stored (normalized) JSON; empty for none. */
    public static List<Row> parse(String json) {
        JsonNode tree = tree(json);
        List<Row> rows = new ArrayList<>();
        if (tree == null || !tree.isArray()) {
            return rows;
        }
        int number = 0;
        for (JsonNode item : tree) {
            number++;
            Map<String, JsonNode> values = new LinkedHashMap<>();
            Map<String, JsonNode> body = new LinkedHashMap<>();
            Map<String, JsonNode> expected = new LinkedHashMap<>();
            String label = null;
            Integer status = null;
            Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                switch (field.getKey()) {
                    case NAME -> label = StringUtils.trimToNull(text(field.getValue()));
                    case STATUS -> status = field.getValue().asInt();
                    case BODY -> field.getValue().fields().forEachRemaining(e -> body.put(e.getKey().trim(), e.getValue()));
                    case EXPECTED -> field.getValue().fields()
                            .forEachRemaining(e -> expected.put(e.getKey().trim(), e.getValue()));
                    default -> values.put(field.getKey(), field.getValue());
                }
            }
            rows.add(new Row(number, label == null ? "Row " + number : label, values, body, expected, status));
        }
        return rows;
    }

    /** Stored rows as plain lists and maps, for the REST view. */
    public static List<Object> toList(String json) {
        JsonNode tree = tree(json);
        if (tree == null || !tree.isArray()) {
            return null;
        }
        return JSON.convertValue(tree, JSON.getTypeFactory().constructCollectionType(List.class, Object.class));
    }

    /** Variable names the rows define: each column as {@code x} and {@code data:x}. */
    public static Set<String> columns(String json) {
        Set<String> names = new LinkedHashSet<>();
        for (Row row : parse(json)) {
            for (String column : row.values().keySet()) {
                names.add(column);
                names.add(DATA_PREFIX + column);
            }
        }
        return names;
    }

    /** Text inside the patches, where {@code {{placeholders}}} may appear. */
    static List<String> patchTexts(String json) {
        List<String> texts = new ArrayList<>();
        for (Row row : parse(json)) {
            row.body().values().forEach(value -> texts.add(value.toString()));
            row.expected().values().forEach(value -> texts.add(value.toString()));
        }
        return texts;
    }

    /** The row's values as run variables ({@code x} and {@code data:x}), with JSON types for non-text values. */
    public static Map<String, String> variables(Row row) {
        Map<String, String> variables = new LinkedHashMap<>();
        row.values().forEach((name, value) -> {
            String text = value == null || value.isNull() ? "" : text(value);
            variables.put(name, text);
            variables.put(DATA_PREFIX + name, text);
            if (value != null && !value.isTextual()) {
                variables.put(CaseVariables.JSON_TYPE_PREFIX + name, value.toString());
                variables.put(CaseVariables.JSON_TYPE_PREFIX + DATA_PREFIX + name, value.toString());
            }
        });
        return variables;
    }

    /** A copy of the case with the row's {@code $body}, {@code $expected} and {@code $status} applied. */
    public static StudioTestCase apply(StudioTestCase testCase, Row row) {
        StudioTestCase copy = EnvironmentOverlay.copy(testCase);
        copy.setDataRows(null);
        if (!row.body().isEmpty()) {
            Object body = testCase.getRequestBody();
            JsonNode tree = body == null ? JsonNodeFactory.instance.objectNode()
                    : body instanceof String text ? readJson(row, "request body", text)
                    : JSON.valueToTree(body);
            JsonNode patched = patch(row, "$body", tree, row.body());
            copy.setRequestBody(body instanceof String ? patched.toString() : JSON.convertValue(patched, Object.class));
        }
        if (!row.expected().isEmpty()) {
            String expected = testCase.getExpectedResponse();
            JsonNode tree = StringUtils.isBlank(expected) || "NA".equalsIgnoreCase(expected.trim())
                    ? JsonNodeFactory.instance.objectNode()
                    : readJson(row, "expected response", expected);
            copy.setExpectedResponse(patch(row, "$expected", tree, row.expected()).toPrettyString());
        }
        if (row.status() != null) {
            copy.setStatusCode(row.status());
        }
        return copy;
    }

    private static JsonNode readJson(Row row, String what, String text) {
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(row.label() + ": the case's " + what
                    + " isn't JSON, so the row's patches can't be applied");
        }
    }

    private static JsonNode patch(Row row, String column, JsonNode tree, Map<String, JsonNode> patches) {
        DocumentContext document = JsonPath.using(PATHS).parse(tree.deepCopy());
        patches.forEach((path, value) -> {
            try {
                document.read(path);
                document.set(path, value);
            } catch (PathNotFoundException missing) {
                Matcher dot = DOT_CHILD.matcher(path);
                Matcher bracket = BRACKET_CHILD.matcher(path);
                String parent = dot.matches() ? dot.group(1) : bracket.matches() ? bracket.group(1) : null;
                String key = dot.matches() ? dot.group(2) : bracket.matches() ? bracket.group(2) : null;
                try {
                    if (parent == null) {
                        throw missing;
                    }
                    document.put(parent, key, value);
                } catch (PathNotFoundException noParent) {
                    throw new IllegalArgumentException(row.label() + ": " + column + " path " + path
                            + " doesn't exist in the case, and neither does its parent");
                }
            }
        });
        return document.json();
    }

    private static String text(JsonNode value) {
        return value.isTextual() ? value.asText() : value.toString();
    }
}
