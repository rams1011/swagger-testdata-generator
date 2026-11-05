package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CaseVariables {

    /** Optional scope qualifiers: {@code {{global:x}}}, {@code {{env:x}}}, {@code {{extract:x}}} read only that layer. */
    public static final String GLOBAL_PREFIX = "global:";
    public static final String ENV_PREFIX = "env:";
    public static final String EXTRACT_PREFIX = "extract:";

    /**
     * Run-scope key holding an extract's JSON value (number, boolean, null, object, array) as JSON text. A quoted
     * whole-value placeholder such as {@code "id": "{{id}}"} in a JSON body or expected response is replaced with
     * that typed value, so an extracted {@code 48} stays {@code 48} rather than becoming {@code "48"}.
     * The NUL character keeps these keys apart from any {@code {{name}}}.
     */
    static final String JSON_TYPE_PREFIX = "\u0000json:";

    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{((?:(?:global|env|extract):)?[A-Za-z_][A-Za-z0-9_]*|\\$[A-Za-z_][A-Za-z0-9_]*)}}");

    private CaseVariables() {
    }

    static StudioTestCase bind(StudioTestCase source, Map<String, String> variables) {
        Map<String, String> vars = variables == null ? Map.of() : variables;
        return bindWith(source, text -> substitute(text, vars), vars);
    }

    private static StudioTestCase bindWith(StudioTestCase source, UnaryOperator<String> resolve,
                                           Map<String, String> vars) {
        StudioTestCase bound = copy(source);
        bound.setBaseUrl(resolve.apply(source.getBaseUrl()));
        bound.setPathParams(substituteMap(source.getPathParams(), resolve));
        bound.setQueryParams(substituteMap(source.getQueryParams(), resolve));
        bound.setHeaders(substituteMap(source.getHeaders(), resolve));
        bound.setCookieParams(substituteMap(source.getCookieParams(), resolve));
        bound.setEndpoint(endpoint(source.getRawPath(), bound.getPathParams(), source.getEndpoint(), resolve));
        bound.setRequestBody(source.getRequestBody() instanceof String text
                ? substituteJsonText(text, resolve, vars)
                : substituteObject(source.getRequestBody(), resolve, vars));
        bound.setExpectedResponse(substituteJsonText(source.getExpectedResponse(), resolve, vars));
        bound.setJsonPathAssertions(resolve.apply(source.getJsonPathAssertions()));
        return bound;
    }

    static List<AssertionCheck> extractInto(Map<String, String> variables, String extracts, String body,
                                           Map<String, List<String>> rawHeaders) {
        return extractInto(variables, null, extracts, body, rawHeaders);
    }

    /** As {@link #extractInto(Map, String, String, Map)}; {@code jsonValues} receives non-string JSON values. */
    static List<AssertionCheck> extractInto(Map<String, String> variables, Map<String, String> jsonValues,
                                           String extracts, String body, Map<String, List<String>> rawHeaders) {
        List<AssertionCheck> checks = new ArrayList<>();
        Map<String, String> rules = JsonPathEvaluator.parseExtracts(extracts);
        if (rules.isEmpty()) {
            return checks;
        }
        JsonElement root = JsonPathEvaluator.parseJson(body);
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            String name = rule.getKey();
            String source = StringUtils.defaultString(rule.getValue());
            String actual;
            boolean passed;
            JsonElement typed = null;
            try {
                JsonElement value = read(source, root, rawHeaders);
                actual = value == null ? null : JsonPathEvaluator.formatValue(value);
                passed = actual != null;
                if (passed && !(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())) {
                    typed = value;
                }
                if (!passed) {
                    actual = "(missing)";
                }
            } catch (IllegalArgumentException exception) {
                actual = exception.getMessage();
                passed = false;
            }
            if (passed) {
                variables.put(name, actual);
                if (jsonValues != null) {
                    if (typed == null) {
                        jsonValues.remove(name);
                    } else {
                        jsonValues.put(name, JSON.toJson(typed));
                    }
                }
            }
            checks.add(new AssertionCheck("extract", name + "=" + source, name, actual, passed));
        }
        return checks;
    }

    static boolean hasPlaceholders(String text) {
        return StringUtils.isNotBlank(text) && PLACEHOLDER.matcher(text).find();
    }

    static Set<String> placeholderNames(StudioTestCase testCase) {
        Set<String> names = new LinkedHashSet<>();
        if (testCase == null) {
            return names;
        }
        collectNames(testCase.getBaseUrl(), names);
        collectNames(testCase.getEndpoint(), names);
        collectNames(testCase.getRawPath(), names);
        collectNames(testCase.getExpectedResponse(), names);
        collectNames(testCase.getJsonPathAssertions(), names);
        collectMap(testCase.getPathParams(), names);
        collectMap(testCase.getQueryParams(), names);
        collectMap(testCase.getHeaders(), names);
        collectMap(testCase.getCookieParams(), names);
        collectObject(testCase.getRequestBody(), names);
        return names;
    }

    static Set<String> extractedNames(String extracts) {
        return new LinkedHashSet<>(JsonPathEvaluator.parseExtracts(extracts).keySet());
    }

    /** Names a case's extracts satisfy: plain {@code {{x}}} and qualified {@code {{extract:x}}}. */
    static Set<String> producedNames(String extracts) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : extractedNames(extracts)) {
            names.add(name);
            names.add(EXTRACT_PREFIX + name);
        }
        return names;
    }

    /** Writes extracted values into the run scope under both {@code x} and {@code extract:x}. */
    static void putExtracted(Map<String, String> variables, Map<String, String> extracted) {
        putExtracted(variables, extracted, Map.of());
    }

    /** Also records each extract's JSON value (from {@code jsonValues}); string extracts clear an older one. */
    static void putExtracted(Map<String, String> variables, Map<String, String> extracted,
                             Map<String, String> jsonValues) {
        if (variables == null || extracted == null) {
            return;
        }
        Map<String, String> types = jsonValues == null ? Map.of() : jsonValues;
        extracted.forEach((name, value) -> {
            variables.put(name, value);
            variables.put(EXTRACT_PREFIX + name, value);
            String json = types.get(name);
            for (String key : List.of(JSON_TYPE_PREFIX + name, JSON_TYPE_PREFIX + EXTRACT_PREFIX + name)) {
                if (json == null) {
                    variables.remove(key);
                } else {
                    variables.put(key, json);
                }
            }
        });
    }

    public static List<StudioTestCase> orderForChain(List<StudioTestCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }
        if (cases.size() == 1) {
            return new ArrayList<>(cases);
        }
        List<StudioTestCase> remaining = new ArrayList<>(cases);
        List<StudioTestCase> ordered = new ArrayList<>(cases.size());
        while (!remaining.isEmpty()) {
            ordered.add(remaining.remove(nextReadyIndex(remaining, ordered)));
        }
        return ordered;
    }

    static List<StudioTestCase> prerequisites(List<StudioTestCase> planCases, StudioTestCase current) {
        return prerequisites(planCases, current, Set.of());
    }

    /** Prior cases whose extracts feed {@code current}; names in {@code defined} already have a value. */
    static List<StudioTestCase> prerequisites(List<StudioTestCase> planCases, StudioTestCase current,
                                              Set<String> defined) {
        Set<String> needed = placeholderNames(current);
        needed.removeAll(defined);
        if (needed.isEmpty() || planCases == null || planCases.isEmpty()) {
            return List.of();
        }
        String currentId = current == null ? null : current.getId();
        List<StudioTestCase> selected = new ArrayList<>();
        Set<String> selectedIds = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StudioTestCase item : planCases) {
                if (item == null || "N".equalsIgnoreCase(StringUtils.trimToEmpty(item.getEnabled()))) {
                    continue;
                }
                if (currentId != null && currentId.equals(item.getId())) {
                    continue;
                }
                if (item.getId() != null && !selectedIds.add(item.getId())) {
                    continue;
                }
                if (item.getId() == null && selected.contains(item)) {
                    continue;
                }
                Set<String> produced = producedNames(item.getExtracts());
                boolean useful = false;
                for (String name : needed) {
                    if (produced.contains(name)) {
                        useful = true;
                        break;
                    }
                }
                if (!useful) {
                    if (item.getId() != null) {
                        selectedIds.remove(item.getId());
                    }
                    continue;
                }
                selected.add(item);
                Set<String> itemNeeds = placeholderNames(item);
                itemNeeds.removeAll(defined);
                needed.addAll(itemNeeds);
                changed = true;
            }
        }
        return orderForChain(selected);
    }

    static String substitute(String text, Map<String, String> variables) {
        if (StringUtils.isBlank(text) || !hasPlaceholders(text)) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = DynamicVariables.isDynamic(name) ? DynamicVariables.value(name) : variables.get(name);
            if (value == null) {
                throw new IllegalArgumentException(unresolvedMessage(name));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String unresolvedMessage(String name) {
        if (name.startsWith(GLOBAL_PREFIX)) {
            return "Unresolved variable {{" + name + "}}. Define " + name.substring(GLOBAL_PREFIX.length())
                    + " in Global variables (or enter its secret value).";
        }
        if (name.startsWith(ENV_PREFIX)) {
            return "Unresolved variable {{" + name + "}}. Define " + name.substring(ENV_PREFIX.length())
                    + " in the selected environment's variables (or enter its secret value).";
        }
        if (name.startsWith(EXTRACT_PREFIX)) {
            return "Unresolved variable {{" + name + "}}. Run a prior case in this plan that extracts "
                    + name.substring(EXTRACT_PREFIX.length()) + ".";
        }
        return "Unresolved variable {{" + name
                + "}}. Define it in the environment or global variables, enter its secret value, "
                + "or run a prior case in this plan that extracts " + name + ".";
    }

    private static int nextReadyIndex(List<StudioTestCase> remaining, List<StudioTestCase> ordered) {
        Set<String> available = new LinkedHashSet<>();
        for (StudioTestCase item : ordered) {
            available.addAll(producedNames(item.getExtracts()));
        }
        Set<String> remainingProduces = new LinkedHashSet<>();
        for (StudioTestCase item : remaining) {
            remainingProduces.addAll(producedNames(item.getExtracts()));
        }
        for (int i = 0; i < remaining.size(); i++) {
            StudioTestCase candidate = remaining.get(i);
            Set<String> producedHere = producedNames(candidate.getExtracts());
            boolean waiting = false;
            for (String name : placeholderNames(candidate)) {
                if (available.contains(name) || producedHere.contains(name)) {
                    continue;
                }
                if (remainingProduces.contains(name)) {
                    waiting = true;
                    break;
                }
            }
            if (!waiting) {
                return i;
            }
        }
        return 0;
    }

    private static void collectMap(Map<String, String> values, Set<String> names) {
        if (values == null) {
            return;
        }
        values.values().forEach(value -> collectNames(value, names));
    }

    private static void collectObject(Object value, Set<String> names) {
        if (value == null) {
            return;
        }
        if (value instanceof String text) {
            collectNames(text, names);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectObject(item, names));
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> collectObject(item, names));
        }
    }

    private static void collectNames(String text, Set<String> names) {
        if (!hasPlaceholders(text)) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            if (!DynamicVariables.isDynamic(matcher.group(1))) {
                names.add(matcher.group(1));
            }
        }
    }

    private static String endpoint(String rawPath, Map<String, String> pathParams, String fallback,
                                   UnaryOperator<String> resolve) {
        String endpoint = StringUtils.defaultIfBlank(rawPath, fallback);
        if (pathParams != null) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                endpoint = endpoint.replace("{" + entry.getKey() + "}", StringUtils.defaultString(entry.getValue()));
            }
        }
        return resolve.apply(StringUtils.defaultIfBlank(endpoint, fallback));
    }

    private static Map<String, String> substituteMap(Map<String, String> source, UnaryOperator<String> resolve) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> copy.put(key, resolve.apply(value)));
        return copy;
    }

    private static Object substituteObject(Object body, UnaryOperator<String> resolve, Map<String, String> vars) {
        if (body == null) {
            return null;
        }
        if (body instanceof String text) {
            JsonElement typed = typedValue(text, vars);
            return typed != null ? toJava(typed) : resolve.apply(text);
        }
        if (body instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), substituteObject(value, resolve, vars)));
            return copy;
        }
        if (body instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(substituteObject(item, resolve, vars));
            }
            return copy;
        }
        return body;
    }

    /**
     * JSON text (raw body, expected response): when it holds a quoted whole-value placeholder for a typed extract,
     * substitutes on the parsed tree so the value keeps its JSON type; otherwise plain text substitution.
     */
    private static String substituteJsonText(String text, UnaryOperator<String> resolve, Map<String, String> vars) {
        if (!hasPlaceholders(text) || !hasTypedValues(vars)) {
            return resolve.apply(text);
        }
        JsonElement root = JsonPathEvaluator.parseJson(text);
        if (root == null || !(root.isJsonObject() || root.isJsonArray()) || !containsTyped(root, vars)) {
            return resolve.apply(text);
        }
        return JSON.toJson(substituteTree(root, resolve, vars));
    }

    private static boolean hasTypedValues(Map<String, String> vars) {
        for (String key : vars.keySet()) {
            if (key.startsWith(JSON_TYPE_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTyped(JsonElement node, Map<String, String> vars) {
        if (node.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                if (containsTyped(entry.getValue(), vars)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isJsonArray()) {
            for (JsonElement item : node.getAsJsonArray()) {
                if (containsTyped(item, vars)) {
                    return true;
                }
            }
            return false;
        }
        return node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()
                && typedValue(node.getAsString(), vars) != null;
    }

    private static JsonElement substituteTree(JsonElement node, UnaryOperator<String> resolve,
                                              Map<String, String> vars) {
        if (node.isJsonObject()) {
            JsonObject copy = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                copy.add(resolve.apply(entry.getKey()), substituteTree(entry.getValue(), resolve, vars));
            }
            return copy;
        }
        if (node.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement item : node.getAsJsonArray()) {
                copy.add(substituteTree(item, resolve, vars));
            }
            return copy;
        }
        if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()) {
            JsonElement typed = typedValue(node.getAsString(), vars);
            return typed != null ? typed : new JsonPrimitive(resolve.apply(node.getAsString()));
        }
        return node;
    }

    /** Typed extract value when {@code text} is exactly one placeholder such as {@code {{id}}}, else null. */
    private static JsonElement typedValue(String text, Map<String, String> vars) {
        if (text == null || vars == null || vars.isEmpty()) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(text.trim());
        if (!matcher.matches()) {
            return null;
        }
        String json = vars.get(JSON_TYPE_PREFIX + matcher.group(1));
        return json == null ? null : JsonPathEvaluator.parseJson(json);
    }

    private static Object toJava(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            value.getAsJsonObject().entrySet().forEach(entry -> map.put(entry.getKey(), toJava(entry.getValue())));
            return map;
        }
        if (value.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            value.getAsJsonArray().forEach(item -> list.add(toJava(item)));
            return list;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return new BigDecimal(primitive.getAsString());
        }
        return primitive.getAsString();
    }

    private static JsonElement read(String source, JsonElement root, Map<String, List<String>> rawHeaders) {
        String spec = source.trim();
        if (spec.regionMatches(true, 0, "header:", 0, 7)) {
            return text(firstHeader(rawHeaders, spec.substring(7).trim()));
        }
        if (spec.regionMatches(true, 0, "cookie:", 0, 7)) {
            return text(cookieValue(rawHeaders, spec.substring(7).trim()));
        }
        if (spec.regionMatches(true, 0, "body:", 0, 5)) {
            spec = spec.substring(5).trim();
        }
        if (!spec.startsWith("$")) {
            throw new IllegalArgumentException("Extract source must be a JSONPath, header:Name, or cookie:Name");
        }
        if (root == null) {
            return null;
        }
        return JsonPathEvaluator.readValue(root, spec);
    }

    private static JsonElement text(String value) {
        return value == null ? null : new JsonPrimitive(value);
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null || StringUtils.isBlank(name)) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static String cookieValue(Map<String, List<String>> headers, String cookieName) {
        if (headers == null || StringUtils.isBlank(cookieName)) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie") || entry.getValue() == null) {
                continue;
            }
            for (String header : entry.getValue()) {
                String value = parseCookie(header, cookieName);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String parseCookie(String setCookie, String cookieName) {
        if (StringUtils.isBlank(setCookie)) {
            return null;
        }
        String first = setCookie.split(";", 2)[0].trim();
        int separator = first.indexOf('=');
        if (separator < 0) {
            return null;
        }
        if (!cookieName.equals(first.substring(0, separator).trim())) {
            return null;
        }
        return first.substring(separator + 1).trim();
    }

    private static StudioTestCase copy(StudioTestCase source) {
        StudioTestCase copy = new StudioTestCase();
        copy.setId(source.getId());
        copy.setSpecId(source.getSpecId());
        copy.setPlanId(source.getPlanId());
        copy.setOperationKey(source.getOperationKey());
        copy.setTestName(source.getTestName());
        copy.setMethod(source.getMethod());
        copy.setRawPath(source.getRawPath());
        copy.setEndpoint(source.getEndpoint());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setContentType(source.getContentType());
        copy.setPathParams(source.getPathParams());
        copy.setQueryParams(source.getQueryParams());
        copy.setHeaders(source.getHeaders());
        copy.setCookieParams(source.getCookieParams());
        copy.setRequestBody(source.getRequestBody());
        copy.setResponseJsonSchema(source.getResponseJsonSchema());
        copy.setStatusCode(source.getStatusCode());
        copy.setResponseType(source.getResponseType());
        copy.setExpectedResponse(source.getExpectedResponse());
        copy.setEnabled(source.getEnabled());
        copy.setLifecycle(source.getLifecycle());
        copy.setTestGroup(source.getTestGroup());
        copy.setJsonPathAssertions(source.getJsonPathAssertions());
        copy.setExtracts(source.getExtracts());
        copy.setMaxResponseTimeMs(source.getMaxResponseTimeMs());
        return copy;
    }
}
