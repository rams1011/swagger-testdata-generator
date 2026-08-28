package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TestRunService {

    private static final String NA = "NA";
    private static final int MAX_BODY_CHARS = 100_000;
    private static final long MIN_TIMEOUT_MS = 10_000L;
    private static final long MAX_TIMEOUT_MS = 60_000L;
    private static final Gson JSON = new Gson();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public RunTestResult execute(StudioTestCase testCase, RunAuth auth) {
        if (testCase == null) {
            throw new IllegalArgumentException("Test case is required");
        }
        Map<String, String> headers = copyMap(testCase.getHeaders());
        Map<String, String> query = copyMap(testCase.getQueryParams());
        applyAuth(headers, query, auth);

        String url;
        try {
            url = buildUrl(testCase.getBaseUrl(), testCase.getEndpoint(), query);
        } catch (IllegalArgumentException exception) {
            return failed(testCase, "", headers, 0, false, List.of(), exception.getMessage());
        }

        String method = StringUtils.defaultIfBlank(testCase.getMethod(), "GET").toUpperCase(Locale.ROOT);
        String body = requestBody(testCase);
        applyContentType(headers, testCase.getContentType(), method, body);

        long maxMs = testCase.getMaxResponseTimeMs() <= 0 ? 3000L : testCase.getMaxResponseTimeMs();
        long timeoutMs = Math.min(MAX_TIMEOUT_MS, Math.max(MIN_TIMEOUT_MS, maxMs + 5_000L));

        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .method(method, bodyPublisher(method, body));
        } catch (IllegalArgumentException exception) {
            return failed(testCase, url, headers, 0, false, List.of(), "Invalid request URL: " + exception.getMessage());
        }
        headers.forEach(builder::header);

        long started = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMs = elapsed(started);
            return evaluate(testCase, url, redact(headers), response.statusCode(), response.headers().map(),
                    truncate(response.body()), elapsedMs, maxMs, null);
        } catch (HttpTimeoutException exception) {
            long elapsedMs = elapsed(started);
            return evaluate(testCase, url, redact(headers), 0, Map.of(), null, elapsedMs, maxMs,
                    "Request timed out after " + timeoutMs + "ms");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed(testCase, url, headers, elapsed(started), true, List.of(), "Run interrupted");
        } catch (Exception exception) {
            return failed(testCase, url, headers, elapsed(started), false, List.of(),
                    StringUtils.defaultIfBlank(exception.getMessage(), exception.getClass().getSimpleName()));
        }
    }

    public RunTestResult skipped(StudioTestCase testCase) {
        String url = "";
        try {
            url = buildUrl(testCase.getBaseUrl(), testCase.getEndpoint(), copyMap(testCase.getQueryParams()));
        } catch (RuntimeException ignored) {
            url = StringUtils.defaultString(testCase.getEndpoint());
        }
        return new RunTestResult(
                false,
                true,
                "Case is disabled (enabled=N)",
                testCase.getId(),
                testCase.getTestName(),
                testCase.getMethod(),
                url,
                testCase.getStatusCode(),
                0,
                false,
                0,
                testCase.getMaxResponseTimeMs(),
                false,
                null,
                Map.of(),
                redact(copyMap(testCase.getHeaders())),
                List.of(),
                null
        );
    }

    private RunTestResult evaluate(StudioTestCase testCase, String url, Map<String, String> requestHeaders,
                                   int actualStatus, Map<String, List<String>> rawHeaders, String body,
                                   long elapsedMs, long maxMs, String error) {
        int expectedStatus = testCase.getStatusCode();
        boolean statusMatched = error == null && actualStatus == expectedStatus;
        boolean timedOut = elapsedMs > maxMs || (error != null && error.toLowerCase(Locale.ROOT).contains("timed out"));
        List<AssertionCheck> assertions = new ArrayList<>();
        assertions.add(new AssertionCheck(
                "status",
                null,
                String.valueOf(expectedStatus),
                actualStatus == 0 ? "" : String.valueOf(actualStatus),
                statusMatched
        ));
        assertions.add(new AssertionCheck(
                "maxResponseTimeMs",
                null,
                String.valueOf(maxMs),
                String.valueOf(elapsedMs),
                !timedOut
        ));
        if (error == null) {
            assertions.addAll(jsonPathChecks(testCase.getJsonPathAssertions(), body));
        }

        boolean assertionsPassed = assertions.stream().allMatch(AssertionCheck::passed);
        boolean passed = error == null && statusMatched && !timedOut && assertionsPassed;
        return new RunTestResult(
                passed,
                false,
                null,
                testCase.getId(),
                testCase.getTestName(),
                testCase.getMethod(),
                url,
                expectedStatus,
                actualStatus,
                statusMatched,
                elapsedMs,
                maxMs,
                timedOut,
                body,
                flattenHeaders(rawHeaders),
                requestHeaders,
                assertions,
                error
        );
    }

    private List<AssertionCheck> jsonPathChecks(String serialized, String body) {
        Map<String, String> expected = JsonPathEvaluator.parseAssertions(serialized);
        if (expected.isEmpty()) {
            return List.of();
        }
        List<AssertionCheck> checks = new ArrayList<>();
        JsonElement root = JsonPathEvaluator.parseJson(body);
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String actual;
            if (root == null) {
                actual = "(not JSON)";
            } else {
                JsonElement value = JsonPathEvaluator.read(root, entry.getKey());
                actual = value == null ? "(missing)" : JsonPathEvaluator.formatValue(value);
            }
            String want = StringUtils.defaultString(entry.getValue());
            boolean match = want.equals(actual);
            checks.add(new AssertionCheck("jsonPath", entry.getKey(), want, actual, match));
        }
        return checks;
    }

    private RunTestResult failed(StudioTestCase testCase, String url, Map<String, String> headers,
                                 long elapsedMs, boolean timedOut, List<AssertionCheck> assertions, String error) {
        return new RunTestResult(
                false,
                false,
                null,
                testCase.getId(),
                testCase.getTestName(),
                testCase.getMethod(),
                url,
                testCase.getStatusCode(),
                0,
                false,
                elapsedMs,
                testCase.getMaxResponseTimeMs(),
                timedOut,
                null,
                Map.of(),
                redact(headers),
                assertions,
                error
        );
    }

    private void applyAuth(Map<String, String> headers, Map<String, String> query, RunAuth auth) {
        if (auth == null) {
            return;
        }
        String type = auth.normalizedType();
        if (RunAuth.NONE.equals(type)) {
            return;
        }
        if (RunAuth.BASIC.equals(type)) {
            if (StringUtils.isBlank(auth.username())) {
                throw new IllegalArgumentException("Username is required for credentials auth");
            }
            String token = Base64.getEncoder().encodeToString(
                    (auth.username() + ":" + StringUtils.defaultString(auth.password()))
                            .getBytes(StandardCharsets.UTF_8));
            putHeader(headers, "Authorization", "Basic " + token);
            return;
        }
        if (RunAuth.BEARER.equals(type)) {
            if (StringUtils.isBlank(auth.token())) {
                throw new IllegalArgumentException("Token is required for bearer auth");
            }
            String token = auth.token().trim();
            if (!token.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = "Bearer " + token;
            }
            putHeader(headers, "Authorization", token);
            return;
        }
        if (RunAuth.API_KEY.equals(type)) {
            if (StringUtils.isBlank(auth.apiKey())) {
                throw new IllegalArgumentException("API key is required");
            }
            String in = StringUtils.defaultIfBlank(auth.apiKeyIn(), "header").trim().toLowerCase(Locale.ROOT);
            if ("query".equals(in)) {
                String name = StringUtils.defaultIfBlank(auth.apiKeyName(), "api_key").trim();
                query.put(name, auth.apiKey());
            } else {
                String name = StringUtils.defaultIfBlank(auth.apiKeyName(), "X-API-Key").trim();
                putHeader(headers, name, auth.apiKey());
            }
        }
    }

    private String requestBody(StudioTestCase testCase) {
        if (testCase.getRequestBody() == null || NA.equalsIgnoreCase(StringUtils.trimToEmpty(testCase.getContentType()))) {
            return null;
        }
        if (testCase.getRequestBody() instanceof String text) {
            return StringUtils.isBlank(text) || NA.equalsIgnoreCase(text.trim()) ? null : text;
        }
        return JSON.toJson(testCase.getRequestBody());
    }

    private void applyContentType(Map<String, String> headers, String contentType, String method, String body) {
        if (body == null || "GET".equals(method) || "HEAD".equals(method)) {
            return;
        }
        if (headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("Content-Type"))) {
            return;
        }
        String mapped = mapContentType(contentType);
        if (mapped != null) {
            headers.put("Content-Type", mapped);
        }
    }

    private String mapContentType(String contentType) {
        if (StringUtils.isBlank(contentType) || NA.equalsIgnoreCase(contentType.trim())) {
            return "application/json";
        }
        String value = contentType.trim();
        if (value.contains("/")) {
            return value;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "JSON" -> "application/json";
            case "STRING", "TEXT" -> "text/plain";
            case "XML" -> "application/xml";
            default -> "application/json";
        };
    }

    private HttpRequest.BodyPublisher bodyPublisher(String method, String body) {
        if (body == null || "GET".equals(method) || "HEAD".equals(method)) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
    }

    static String buildUrl(String baseUrl, String endpoint, Map<String, String> query) {
        String base = StringUtils.trimToEmpty(baseUrl).replaceAll("/+$", "");
        String path = StringUtils.trimToEmpty(endpoint);
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return appendQuery(path, query);
        }
        if (base.isEmpty()) {
            throw new IllegalArgumentException("Base URL is required");
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException("Base URL must start with http:// or https://");
        }
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return appendQuery(base + path, query);
    }

    private static String appendQuery(String url, Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return url;
        }
        StringBuilder builder = new StringBuilder(url);
        boolean first = !url.contains("?");
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (StringUtils.isBlank(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            builder.append(first ? '?' : '&');
            first = false;
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
        headers.put(name, value);
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static Map<String, String> flattenHeaders(Map<String, List<String>> headers) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (headers == null) {
            return flat;
        }
        headers.forEach((name, values) -> {
            if (values != null && !values.isEmpty()) {
                flat.put(name, String.join(", ", values));
            }
        });
        return flat;
    }

    private static Map<String, String> redact(Map<String, String> headers) {
        Map<String, String> copy = new LinkedHashMap<>();
        headers.forEach((name, value) -> copy.put(name, redactHeader(name, value)));
        return copy;
    }

    private static String redactHeader(String name, String value) {
        if (value == null) {
            return null;
        }
        if (name != null && name.equalsIgnoreCase("Authorization")) {
            int space = value.indexOf(' ');
            return space > 0 ? value.substring(0, space) + " ***" : "***";
        }
        if (name != null && (name.toLowerCase(Locale.ROOT).contains("api-key")
                || name.toLowerCase(Locale.ROOT).contains("apikey")
                || name.equalsIgnoreCase("X-API-Key"))) {
            return "***";
        }
        return value;
    }

    private static String truncate(String body) {
        if (body == null || body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        return body.substring(0, MAX_BODY_CHARS) + "…";
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
