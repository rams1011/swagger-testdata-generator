package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TestRunService {

    private static final String NA = "NA";
    private static final int MAX_BODY_CHARS = 100_000;
    private static final long MIN_TIMEOUT_MS = 10_000L;
    private static final long MAX_TIMEOUT_MS = 60_000L;
    private static final Gson JSON = new Gson();

    private static final Pattern FILENAME = Pattern.compile("filename\\*?=(?:UTF-8''|\"?)([^\";]+)", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final StudioStorage studioStorage;
    private final StudioProperties properties;

    public TestRunService(StudioStorage studioStorage, StudioProperties properties) {
        this.studioStorage = studioStorage;
        this.properties = properties;
    }

    public RunTestResult execute(StudioTestCase testCase, RunAuth auth) {
        if (testCase == null) {
            throw new IllegalArgumentException("Test case is required");
        }
        Map<String, String> headers = copyMap(testCase.getHeaders());
        Map<String, String> query = copyMap(testCase.getQueryParams());
        Map<String, String> cookies = copyMap(testCase.getCookieParams());
        applyAuth(headers, query, cookies, auth);
        String cookieHeader = HttpPayloadSupport.cookieHeader(cookies);
        if (StringUtils.isNotBlank(cookieHeader)) {
            HttpPayloadSupport.putHeader(headers, "Cookie", cookieHeader);
        }

        String url;
        try {
            url = buildUrl(testCase.getBaseUrl(), testCase.getEndpoint(), query);
        } catch (IllegalArgumentException exception) {
            return failed(testCase, "", headers, 0, false, List.of(), exception.getMessage());
        }

        String method = StringUtils.defaultIfBlank(testCase.getMethod(), "GET").toUpperCase(Locale.ROOT);
        boolean multipart = RequestFileSupport.isMultipartContentType(testCase.getContentType())
                || RequestFileSupport.hasFileRefs(testCase.getRequestBody());
        byte[] binaryBody = null;
        String textBody = null;
        if (multipart && !"GET".equals(method) && !"HEAD".equals(method)) {
            RequestFileSupport.MultipartPayload payload =
                    RequestFileSupport.buildMultipart(testCase.getRequestBody(), testCase.getId(), studioStorage);
            binaryBody = payload.body();
            HttpPayloadSupport.putHeader(headers, "Content-Type", payload.contentType());
        } else {
            textBody = requestBody(testCase);
            applyContentType(headers, testCase.getContentType(), method, textBody);
        }

        long maxMs = testCase.getMaxResponseTimeMs() <= 0 ? 3000L : testCase.getMaxResponseTimeMs();
        long timeoutMs = Math.min(MAX_TIMEOUT_MS, Math.max(MIN_TIMEOUT_MS, maxMs + 5_000L));

        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .method(method, binaryBody != null
                            ? HttpRequest.BodyPublishers.ofByteArray(binaryBody)
                            : bodyPublisher(method, textBody));
        } catch (IllegalArgumentException exception) {
            return failed(testCase, url, headers, 0, false, List.of(), "Invalid request URL: " + exception.getMessage());
        }
        headers.forEach(builder::header);

        long started = System.nanoTime();
        try {
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            long elapsedMs = elapsed(started);
            Map<String, List<String>> rawHeaders = response.headers().map();
            byte[] bytes = response.body() == null ? new byte[0] : response.body();
            String contentType = firstHeader(rawHeaders, "Content-Type");
            boolean binary = isBinaryResponse(testCase, contentType, bytes);
            String downloadPath = null;
            String bodyText;
            if (binary) {
                downloadPath = saveDownload(testCase, bytes, contentType, firstHeader(rawHeaders, "Content-Disposition"));
                bodyText = "[binary " + bytes.length + " bytes" + (downloadPath == null ? "" : " saved to " + downloadPath) + "]";
            } else {
                bodyText = truncate(new String(bytes, charsetOf(contentType)));
            }
            return evaluate(testCase, url, redact(headers), response.statusCode(), rawHeaders,
                    bodyText, downloadPath, elapsedMs, maxMs, null);
        } catch (HttpTimeoutException exception) {
            long elapsedMs = elapsed(started);
            return evaluate(testCase, url, redact(headers), 0, Map.of(), null, null, elapsedMs, maxMs,
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
                null,
                null
        );
    }

    private RunTestResult evaluate(StudioTestCase testCase, String url, Map<String, String> requestHeaders,
                                   int actualStatus, Map<String, List<String>> rawHeaders, String body,
                                   String downloadPath, long elapsedMs, long maxMs, String error) {
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
            AssertionCheck expectedBody = expectedBodyCheck(testCase, body);
            if (expectedBody != null) {
                assertions.add(expectedBody);
            }
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
                error,
                downloadPath
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
                error,
                null
        );
    }

    private AssertionCheck expectedBodyCheck(StudioTestCase testCase, String body) {
        String expected = testCase.getExpectedResponse();
        if (StringUtils.isBlank(expected) || NA.equalsIgnoreCase(expected.trim())) {
            return null;
        }
        if ("FILE".equalsIgnoreCase(StringUtils.trimToEmpty(testCase.getResponseType()))) {
            return null;
        }
        String actual = StringUtils.defaultString(body);
        boolean match = jsonEquals(expected, actual) || expected.trim().equals(actual.trim());
        return new AssertionCheck("expectedResponse", null, truncate(expected.trim()), truncate(actual), match);
    }

    private static boolean jsonEquals(String left, String right) {
        try {
            JsonElement expected = JsonParser.parseString(left);
            JsonElement actual = JsonParser.parseString(right);
            return expected.equals(actual);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void applyAuth(Map<String, String> headers, Map<String, String> query, Map<String, String> cookies, RunAuth auth) {
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
            HttpPayloadSupport.putHeader(headers, "Authorization", "Basic " + token);
            return;
        }
        if (RunAuth.BEARER.equals(type) || (RunAuth.OAUTH2.equals(type) && StringUtils.isNotBlank(auth.token()))) {
            applyBearer(headers, auth.token());
            return;
        }
        if (RunAuth.OAUTH2.equals(type)) {
            applyBearer(headers, fetchOAuthToken(auth));
            return;
        }
        if (RunAuth.API_KEY.equals(type)) {
            if (StringUtils.isBlank(auth.apiKey())) {
                throw new IllegalArgumentException("API key is required");
            }
            String in = StringUtils.defaultIfBlank(auth.apiKeyIn(), "header").trim().toLowerCase(Locale.ROOT);
            if ("query".equals(in)) {
                query.put(StringUtils.defaultIfBlank(auth.apiKeyName(), "api_key").trim(), auth.apiKey());
            } else if ("cookie".equals(in)) {
                cookies.put(StringUtils.defaultIfBlank(auth.apiKeyName(), "api_key").trim(), auth.apiKey());
            } else {
                HttpPayloadSupport.putHeader(headers, StringUtils.defaultIfBlank(auth.apiKeyName(), "X-API-Key").trim(), auth.apiKey());
            }
        }
    }

    private void applyBearer(Map<String, String> headers, String rawToken) {
        if (StringUtils.isBlank(rawToken)) {
            throw new IllegalArgumentException("Token is required for bearer auth");
        }
        String token = rawToken.trim();
        if (!token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = "Bearer " + token;
        }
        HttpPayloadSupport.putHeader(headers, "Authorization", token);
    }

    private String fetchOAuthToken(RunAuth auth) {
        if (StringUtils.isBlank(auth.tokenUrl())) {
            throw new IllegalArgumentException("tokenUrl is required for OAuth2, or paste a bearer token");
        }
        String grant = StringUtils.defaultIfBlank(auth.grantType(), "client_credentials").trim();
        if ("authorization_code".equalsIgnoreCase(grant) || "implicit".equalsIgnoreCase(grant)) {
            throw new IllegalArgumentException("This OAuth flow needs a bearer token pasted in Run authentication");
        }
        if (StringUtils.isBlank(auth.clientId()) || StringUtils.isBlank(auth.clientSecret())) {
            throw new IllegalArgumentException("Client id and secret are required for OAuth2");
        }
        StringBuilder form = new StringBuilder();
        form.append("grant_type=").append(java.net.URLEncoder.encode(grant, StandardCharsets.UTF_8));
        form.append("&client_id=").append(java.net.URLEncoder.encode(auth.clientId(), StandardCharsets.UTF_8));
        form.append("&client_secret=").append(java.net.URLEncoder.encode(auth.clientSecret(), StandardCharsets.UTF_8));
        if ("password".equalsIgnoreCase(grant)) {
            if (StringUtils.isBlank(auth.username()) || StringUtils.isBlank(auth.password())) {
                throw new IllegalArgumentException("Username and password are required for the OAuth password grant");
            }
            form.append("&username=").append(java.net.URLEncoder.encode(auth.username(), StandardCharsets.UTF_8));
            form.append("&password=").append(java.net.URLEncoder.encode(auth.password(), StandardCharsets.UTF_8));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(auth.tokenUrl().trim()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("OAuth token request failed HTTP " + response.statusCode());
            }
            JsonElement parsed = JsonParser.parseString(StringUtils.defaultString(response.body()));
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("access_token")) {
                throw new IllegalArgumentException("OAuth token response did not include access_token");
            }
            return parsed.getAsJsonObject().get("access_token").getAsString();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("OAuth token request interrupted");
        } catch (Exception exception) {
            throw new IllegalArgumentException("OAuth token request failed: " + exception.getMessage());
        }
    }

    private String requestBody(StudioTestCase testCase) {
        if (testCase.getRequestBody() == null || NA.equalsIgnoreCase(StringUtils.trimToEmpty(testCase.getContentType()))) {
            return null;
        }
        if (HttpPayloadSupport.isFormUrlEncoded(testCase.getContentType())) {
            return HttpPayloadSupport.formUrlEncoded(testCase.getRequestBody());
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
            case "FORM", "URLENCODED" -> "application/x-www-form-urlencoded";
            case "MULTIPART" -> null;
            case "FILE" -> "application/octet-stream";
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
            return HttpPayloadSupport.appendQuery(path, query);
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
        return HttpPayloadSupport.appendQuery(base + path, query);
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
                || name.equalsIgnoreCase("X-API-Key")
                || name.equalsIgnoreCase("Cookie"))) {
            return "***";
        }
        return value;
    }

    private String saveDownload(StudioTestCase testCase, byte[] bytes, String contentType, String disposition) {
        Path dir = Path.of(properties.getDefaultOutputDir()).resolve("downloads");
        try {
            Files.createDirectories(dir);
            String name = downloadFileName(testCase, contentType, disposition);
            Path file = dir.resolve(name);
            Files.write(file, bytes);
            return file.toAbsolutePath().toString();
        } catch (Exception exception) {
            return null;
        }
    }

    private static String downloadFileName(StudioTestCase testCase, String contentType, String disposition) {
        if (StringUtils.isNotBlank(disposition)) {
            Matcher matcher = FILENAME.matcher(disposition);
            if (matcher.find()) {
                return RequestFileSupport.sanitizeFileName(matcher.group(1));
            }
        }
        String ext = extensionFor(contentType);
        String id = StringUtils.defaultIfBlank(testCase.getId(), "download");
        return id + ext;
    }

    private static String extensionFor(String contentType) {
        String type = StringUtils.defaultString(contentType).toLowerCase(Locale.ROOT);
        if (type.contains("pdf")) {
            return ".pdf";
        }
        if (type.contains("zip")) {
            return ".zip";
        }
        if (type.contains("png")) {
            return ".png";
        }
        if (type.contains("jpeg") || type.contains("jpg")) {
            return ".jpg";
        }
        if (type.contains("json")) {
            return ".json";
        }
        return ".bin";
    }

    private static boolean isBinaryResponse(StudioTestCase testCase, String contentType, byte[] bytes) {
        if ("FILE".equalsIgnoreCase(StringUtils.trimToEmpty(testCase.getResponseType()))) {
            return true;
        }
        String type = StringUtils.defaultString(contentType).toLowerCase(Locale.ROOT);
        if (type.contains("json") || type.contains("xml") || type.startsWith("text/") || type.contains("urlencoded")) {
            return false;
        }
        if (type.contains("octet-stream") || type.contains("pdf") || type.contains("zip")
                || type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/")) {
            return true;
        }
        if (bytes == null) {
            return false;
        }
        int limit = Math.min(bytes.length, 512);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) {
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

    private static Charset charsetOf(String contentType) {
        if (StringUtils.isBlank(contentType)) {
            return StandardCharsets.UTF_8;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        int charset = lower.indexOf("charset=");
        if (charset < 0) {
            return StandardCharsets.UTF_8;
        }
        String name = contentType.substring(charset + 8).replace("\"", "").split("[; ]")[0].trim();
        try {
            return Charset.forName(name);
        } catch (RuntimeException ignored) {
            return StandardCharsets.UTF_8;
        }
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
