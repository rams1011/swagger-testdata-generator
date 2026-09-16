package com.tx.api.test.generator.studio.service;

import com.google.gson.JsonElement;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CaseVariables {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z_][A-Za-z0-9_]*)}}");

    private CaseVariables() {
    }

    static StudioTestCase bind(StudioTestCase source, Map<String, String> variables) {
        Map<String, String> vars = variables == null ? Map.of() : variables;
        StudioTestCase bound = copy(source);
        bound.setBaseUrl(substitute(source.getBaseUrl(), vars));
        bound.setPathParams(substituteMap(source.getPathParams(), vars));
        bound.setQueryParams(substituteMap(source.getQueryParams(), vars));
        bound.setHeaders(substituteMap(source.getHeaders(), vars));
        bound.setCookieParams(substituteMap(source.getCookieParams(), vars));
        bound.setEndpoint(endpoint(source.getRawPath(), bound.getPathParams(), source.getEndpoint(), vars));
        bound.setRequestBody(substituteObject(source.getRequestBody(), vars));
        bound.setExpectedResponse(substitute(source.getExpectedResponse(), vars));
        bound.setJsonPathAssertions(substitute(source.getJsonPathAssertions(), vars));
        return bound;
    }

    static List<AssertionCheck> extractInto(Map<String, String> variables, String extracts, String body,
                                           Map<String, List<String>> rawHeaders) {
        List<AssertionCheck> checks = new ArrayList<>();
        Map<String, String> rules = JsonPathEvaluator.parseAssertions(extracts);
        if (rules.isEmpty()) {
            return checks;
        }
        JsonElement root = JsonPathEvaluator.parseJson(body);
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            String name = rule.getKey();
            String source = StringUtils.defaultString(rule.getValue());
            String actual;
            boolean passed;
            try {
                actual = read(source, root, rawHeaders);
                passed = actual != null;
                if (!passed) {
                    actual = "(missing)";
                }
            } catch (IllegalArgumentException exception) {
                actual = exception.getMessage();
                passed = false;
            }
            if (passed) {
                variables.put(name, actual);
            }
            checks.add(new AssertionCheck("extract", name + "=" + source, name, actual, passed));
        }
        return checks;
    }

    static boolean hasPlaceholders(String text) {
        return StringUtils.isNotBlank(text) && PLACEHOLDER.matcher(text).find();
    }

    static String substitute(String text, Map<String, String> variables) {
        if (StringUtils.isBlank(text) || !hasPlaceholders(text)) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.get(name);
            if (value == null) {
                throw new IllegalArgumentException("Unresolved variable {{" + name
                        + "}}. Run a prior case in this plan that extracts " + name + ".");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String endpoint(String rawPath, Map<String, String> pathParams, String fallback,
                                   Map<String, String> variables) {
        String endpoint = StringUtils.defaultIfBlank(rawPath, fallback);
        if (pathParams != null) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                endpoint = endpoint.replace("{" + entry.getKey() + "}", StringUtils.defaultString(entry.getValue()));
            }
        }
        return substitute(StringUtils.defaultIfBlank(endpoint, fallback), variables);
    }

    private static Map<String, String> substituteMap(Map<String, String> source, Map<String, String> variables) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> copy.put(key, substitute(value, variables)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object substituteObject(Object body, Map<String, String> variables) {
        if (body == null) {
            return null;
        }
        if (body instanceof String text) {
            return substitute(text, variables);
        }
        if (body instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), substituteObject(value, variables)));
            return copy;
        }
        if (body instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(substituteObject(item, variables));
            }
            return copy;
        }
        return body;
    }

    private static String read(String source, JsonElement root, Map<String, List<String>> rawHeaders) {
        String spec = source.trim();
        if (spec.regionMatches(true, 0, "header:", 0, 7)) {
            return firstHeader(rawHeaders, spec.substring(7).trim());
        }
        if (spec.regionMatches(true, 0, "cookie:", 0, 7)) {
            return cookieValue(rawHeaders, spec.substring(7).trim());
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
        JsonElement value = JsonPathEvaluator.read(root, spec);
        if (value == null) {
            return null;
        }
        return JsonPathEvaluator.formatValue(value);
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
        copy.setTestGroup(source.getTestGroup());
        copy.setJsonPathAssertions(source.getJsonPathAssertions());
        copy.setExtracts(source.getExtracts());
        copy.setMaxResponseTimeMs(source.getMaxResponseTimeMs());
        return copy;
    }
}
