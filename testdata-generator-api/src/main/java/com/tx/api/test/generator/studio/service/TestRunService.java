package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.tx.api.test.generator.studio.audit.StudioAuditService;
import com.tx.api.test.generator.studio.collection.HeaderRules;
import com.tx.api.test.generator.studio.collection.StudioEnvironmentNetwork;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
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
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TestRunService {

    private static final Logger log = LoggerFactory.getLogger(TestRunService.class);
    private static final String NA = "NA";
    private static final int DEFAULT_MAX_BODY_CHARS = 1_000_000;
    private static final long MIN_TIMEOUT_MS = 10_000L;
    private static final long DEFAULT_MAX_TIMEOUT_MS = 300_000L;
    static final int MAX_REDIRECTS = 10;
    /** Keeps a {@code null} the body sends on purpose, such as {@code "middleName": null}. */
    private static final Gson JSON = new com.google.gson.GsonBuilder().serializeNulls().create();

    private static final Pattern FILENAME = Pattern.compile("filename\\*?=(?:UTF-8''|\"?)([^\";]+)", Pattern.CASE_INSENSITIVE);

    // Redirects are never followed by the client: a public response must not redirect this process onto an internal
    // address, so redirects (when the environment allows them) are followed here, checking every hop.
    private final NetworkClients networkClients = new NetworkClients();
    private final OAuthTokenCache oauthTokens = new OAuthTokenCache();
    private final StudioStorage studioStorage;
    private final StudioProperties properties;
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final StudioAuditService auditService;

    public TestRunService(StudioStorage studioStorage, StudioProperties properties, OutboundUrlPolicy outboundUrlPolicy) {
        this(studioStorage, properties, outboundUrlPolicy, null);
    }

    @Autowired
    public TestRunService(StudioStorage studioStorage, StudioProperties properties, OutboundUrlPolicy outboundUrlPolicy,
                          StudioAuditService auditService) {
        this.studioStorage = studioStorage;
        this.properties = properties;
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.auditService = auditService;
    }

    public RunTestResult execute(StudioTestCase testCase, RunAuth auth) {
        return execute(testCase, auth, new LinkedHashMap<>());
    }

    public RunTestResult execute(StudioTestCase testCase, RunAuth auth, Map<String, String> variables) {
        return execute(testCase, auth, variables, null);
    }

    /**
     * {@code auth} is the run's authentication (from Run authentication); a case with its own auth uses that instead.
     * {@code network} holds the environment's connection settings (null for defaults).
     */
    public RunTestResult execute(StudioTestCase testCase, RunAuth auth, Map<String, String> variables,
                                 StudioEnvironmentNetwork network) {
        return execute(testCase, auth, variables, network, OutboundUrlPolicy.Scope.NONE);
    }

    /** {@code scope} is the environment whose private target allowlist applies to this request. */
    public RunTestResult execute(StudioTestCase testCase, RunAuth auth, Map<String, String> variables,
                                 StudioEnvironmentNetwork network, OutboundUrlPolicy.Scope scope) {
        if (testCase == null) {
            throw new IllegalArgumentException("Test case is required");
        }
        Map<String, String> store = variables == null ? new LinkedHashMap<>() : variables;
        StudioTestCase bound;
        try {
            bound = CaseVariables.bind(testCase, store);
        } catch (IllegalArgumentException exception) {
            return failed(testCase, "", Map.of(), 0, false, List.of(), exception.getMessage());
        }
        // A blank header or cookie is an optional one left unset, as a blank query value is (HttpPayloadSupport).
        Map<String, String> headers = filled(bound.getHeaders());
        Map<String, String> query = copyMap(bound.getQueryParams());
        Map<String, String> cookies = filled(bound.getCookieParams());
        RunAuth effectiveAuth = bound.getAuth() != null ? bound.getAuth() : auth;
        RunAuth oauthUsed;
        try {
            oauthUsed = applyAuth(headers, query, cookies, effectiveAuth, scope);
            String problem = HeaderRules.firstProblem(headers);
            if (problem != null) {
                throw new IllegalArgumentException(StringUtils.capitalize(problem));
            }
        } catch (IllegalArgumentException exception) {
            // One case's bad header or missing credential fails that case, not the whole plan.
            return failed(bound, "", headers, 0, false, List.of(), exception.getMessage());
        }
        String cookieHeader = HttpPayloadSupport.cookieHeader(cookies);
        if (StringUtils.isNotBlank(cookieHeader)) {
            HttpPayloadSupport.putHeader(headers, "Cookie", cookieHeader);
        }

        String url;
        OutboundUrlPolicy.Target target;
        try {
            url = buildUrl(bound.getBaseUrl(), bound.getEndpoint(), query);
            target = outboundUrlPolicy.check(url, scope);
        } catch (IllegalArgumentException exception) {
            return failed(bound, "", headers, 0, false, List.of(), exception.getMessage());
        }

        String method = StringUtils.defaultIfBlank(bound.getMethod(), "GET").toUpperCase(Locale.ROOT);
        boolean multipart = RequestFileSupport.isMultipartContentType(bound.getContentType())
                || RequestFileSupport.hasFileRefs(bound.getRequestBody());
        byte[] binaryBody = null;
        String textBody = null;
        if (multipart && !"GET".equals(method) && !"HEAD".equals(method)) {
            try {
                RequestFileSupport.MultipartPayload payload =
                        RequestFileSupport.buildMultipart(bound.getRequestBody(), bound.getId(), studioStorage);
                binaryBody = payload.body();
                HttpPayloadSupport.putHeader(headers, "Content-Type", payload.contentType());
            } catch (IllegalArgumentException | NoSuchElementException exception) {
                return failed(bound, url, headers, 0, false, List.of(), exception.getMessage());
            }
        } else {
            textBody = requestBody(bound);
            applyContentType(headers, bound.getContentType(), method, textBody);
        }

        String sentBody = binaryBody != null ? "[multipart body, " + binaryBody.length + " bytes]" : textBody;
        long maxMs = bound.getMaxResponseTimeMs() <= 0 ? 3000L : bound.getMaxResponseTimeMs();
        long timeoutMs = timeoutMs(maxMs, network);
        NetworkClients.Channel channel;
        try {
            OutboundUrlPolicy.Target proxy = null;
            if (network != null && StringUtils.isNotBlank(network.proxyUrl())) {
                proxy = outboundUrlPolicy.check(StudioEnvironmentNetwork.proxyUri(network.proxyUrl()).toString(), scope);
                if (proxy.address() == null) {
                    throw new IllegalArgumentException("Proxy host " + proxy.host() + " could not be resolved.");
                }
                auditPrivateTarget(proxy, "CONNECT", scope);
            }
            channel = networkClients.channel(network, proxy);
        } catch (IllegalArgumentException exception) {
            return failed(bound, url, headers, 0, false, List.of(), exception.getMessage());
        }

        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .method(method, binaryBody != null
                            ? HttpRequest.BodyPublishers.ofByteArray(binaryBody)
                            : bodyPublisher(method, textBody));
        } catch (IllegalArgumentException exception) {
            return failed(bound, url, headers, 0, false, List.of(), "The request URL is not valid.");
        }
        headers.forEach(builder::header);

        long started = System.nanoTime();
        List<String> hops = new ArrayList<>();
        try {
            auditPrivateTarget(target, method, scope);
            HttpResponse<byte[]> response = channel.send(builder.build(), payload(method, binaryBody, textBody), target);
            if (network != null && network.followsRedirects()) {
                response = followRedirects(channel, response, method, headers, binaryBody, textBody, timeoutMs, hops,
                        scope);
            }
            if (response.statusCode() == 401 && oauthUsed != null && oauthTokens.invalidate(oauthUsed)) {
                log.info("API answered 401; dropped the cached OAuth token so the next request fetches a new one");
            }
            long elapsedMs = elapsed(started);
            Map<String, List<String>> rawHeaders = response.headers().map();
            byte[] bytes = response.body() == null ? new byte[0] : response.body();
            String contentType = firstHeader(rawHeaders, "Content-Type");
            boolean binary = isBinaryResponse(bound, contentType, bytes);
            String downloadPath = null;
            String bodyText;
            if (binary) {
                downloadPath = saveDownload(bound, bytes, contentType, firstHeader(rawHeaders, "Content-Disposition"));
                bodyText = "[binary " + bytes.length + " bytes" + (downloadPath == null ? "" : " saved to " + downloadPath) + "]";
            } else {
                bodyText = new String(bytes, charsetOf(contentType));
            }
            RunTestResult result = evaluate(bound, url, redact(headers), response.statusCode(), rawHeaders,
                    bodyText, downloadPath, bytes, elapsedMs, maxMs, null, store).withRequestBody(sentBody);
            return hops.isEmpty() ? result : withRedirects(result, hops);
        } catch (HttpTimeoutException exception) {
            long elapsedMs = elapsed(started);
            return evaluate(bound, url, redact(headers), 0, Map.of(), null, null, null, elapsedMs, maxMs,
                    "Request timed out after " + timeoutMs + "ms", store).withRequestBody(sentBody);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed(bound, url, headers, elapsed(started), true, List.of(), "Run interrupted");
        } catch (IllegalArgumentException exception) {
            return failed(bound, url, headers, elapsed(started), false, List.of(), exception.getMessage());
        } catch (javax.net.ssl.SSLException exception) {
            log.info("TLS failed for {}: {}", urlForLog(url), exception.getMessage());
            return failed(bound, url, headers, elapsed(started), false, List.of(), "TLS handshake failed: "
                    + StringUtils.defaultIfBlank(exception.getMessage(), "certificate not trusted")
                    + ". Add the server's CA certificate to the environment, or turn off Verify SSL for a test server.");
        } catch (Exception exception) {
            log.warn("Run request failed: {}", exception.toString());
            return failed(bound, url, headers, elapsed(started), false, List.of(), sendFailure(exception, url, network));
        }
    }

    /** Why a request could not be sent, in words a tester can act on (host, port and proxy only; no query). */
    static String sendFailure(Throwable exception, String url, StudioEnvironmentNetwork network) {
        String target = "the server";
        try {
            URI uri = URI.create(url);
            int port = uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            target = uri.getHost() + ":" + port;
        } catch (RuntimeException ignored) {
            // keep the generic wording
        }
        boolean proxied = network != null && StringUtils.isNotBlank(network.proxyUrl());
        List<Throwable> causes = new ArrayList<>();
        for (Throwable cause = exception; cause != null && causes.size() < 10;
             cause = cause.getCause() == cause ? null : cause.getCause()) {
            causes.add(cause);
        }
        // The innermost specific cause wins: a ConnectException often wraps an unresolved host.
        if (causes.stream().anyMatch(cause -> cause instanceof java.nio.channels.UnresolvedAddressException
                || cause instanceof java.net.UnknownHostException)) {
            return "Host not found: " + target.replaceAll(":\\d+$", "") + ". Check the base URL and the selected "
                    + "environment" + (proxied ? ", or the proxy" : "") + ".";
        }
        String tunnel = causes.stream().map(cause -> StringUtils.defaultString(cause.getMessage()))
                .filter(message -> message.startsWith("Tunnel failed")).findFirst().orElse(null);
        if (tunnel != null) {
            return "The proxy refused the connection to " + target + " (" + tunnel.replace("Tunnel failed, got: ", "HTTP ")
                    + "). Check the environment's proxy setting or the server's proxy rules.";
        }
        if (causes.stream().anyMatch(cause -> cause instanceof java.net.http.HttpConnectTimeoutException)) {
            return "Could not connect to " + target + " within 10 seconds. The host may be down, blocked by a "
                    + "firewall, or reachable only through a proxy or VPN.";
        }
        if (causes.stream().anyMatch(cause -> cause instanceof java.net.ConnectException)) {
            return "Connection refused by " + target + (proxied ? " (or the proxy)" : "")
                    + ". Nothing is listening there: check the port and that the service is running.";
        }
        if (causes.stream().anyMatch(cause -> cause instanceof java.io.EOFException
                || StringUtils.defaultString(cause.getMessage()).toLowerCase(Locale.ROOT).contains("connection reset"))) {
            return "The connection to " + target + " was closed before a response arrived. The server may "
                    + "have crashed, or expects HTTPS instead of HTTP (or the other way round).";
        }
        return "The request to " + target + " could not be sent.";
    }

    /** The environment's request timeout, else the case's max response time + 5 s; capped by the server setting. */
    long timeoutMs(long maxResponseTimeMs, StudioEnvironmentNetwork network) {
        long cap = Math.max(MIN_TIMEOUT_MS, properties == null ? DEFAULT_MAX_TIMEOUT_MS
                : properties.getRun().getMaxRequestTimeoutMs());
        if (network != null && network.requestTimeoutMs() != null) {
            return Math.min(cap, Math.max(StudioEnvironmentNetwork.MIN_TIMEOUT_MS, network.requestTimeoutMs()));
        }
        return Math.min(cap, Math.max(MIN_TIMEOUT_MS, maxResponseTimeMs + 5_000L));
    }

    /**
     * Follows 301/302/303/307/308 up to {@link #MAX_REDIRECTS} hops. Each target passes the outbound URL policy and
     * is connected to by its validated address.
     * 303 (and 301/302 after a POST) switch to GET without a body, like browsers and curl; 307/308 keep the method
     * and body. Authorization and Cookie headers are dropped when the redirect leaves the original host.
     */
    private HttpResponse<byte[]> followRedirects(NetworkClients.Channel channel, HttpResponse<byte[]> first, String method,
                                                 Map<String, String> headers, byte[] binaryBody, String textBody,
                                                 long timeoutMs, List<String> hops,
                                                 OutboundUrlPolicy.Scope scope) throws Exception {
        HttpResponse<byte[]> response = first;
        String currentMethod = method;
        byte[] currentBinary = binaryBody;
        String currentText = textBody;
        Map<String, String> currentHeaders = new LinkedHashMap<>(headers);
        String originalHost = StringUtils.defaultString(first.uri().getHost()).toLowerCase(Locale.ROOT);
        while (isRedirect(response.statusCode())) {
            String location = response.headers().firstValue("Location").orElse(null);
            if (StringUtils.isBlank(location)) {
                return response;
            }
            if (hops.size() >= MAX_REDIRECTS) {
                throw new IllegalArgumentException("Stopped after " + MAX_REDIRECTS + " redirects");
            }
            URI next = response.uri().resolve(location.trim());
            OutboundUrlPolicy.Target nextTarget = outboundUrlPolicy.check(next.toString(), scope);
            int status = response.statusCode();
            if (status == 303 || ((status == 301 || status == 302) && !"GET".equals(currentMethod)
                    && !"HEAD".equals(currentMethod))) {
                currentMethod = "HEAD".equals(currentMethod) ? "HEAD" : "GET";
                currentBinary = null;
                currentText = null;
                currentHeaders.keySet().removeIf(name -> name.equalsIgnoreCase("Content-Type"));
            }
            if (!originalHost.equals(StringUtils.defaultString(next.getHost()).toLowerCase(Locale.ROOT))) {
                currentHeaders.keySet().removeIf(name -> name.equalsIgnoreCase("Authorization")
                        || name.equalsIgnoreCase("Cookie"));
            }
            hops.add(status + " → " + urlForLog(next.toString()));
            HttpRequest.Builder builder = HttpRequest.newBuilder(next)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .method(currentMethod, currentBinary != null
                            ? HttpRequest.BodyPublishers.ofByteArray(currentBinary)
                            : bodyPublisher(currentMethod, currentText));
            currentHeaders.forEach(builder::header);
            auditPrivateTarget(nextTarget, currentMethod, scope);
            response = channel.send(builder.build(), payload(currentMethod, currentBinary, currentText), nextTarget);
        }
        return response;
    }

    private static byte[] payload(String method, byte[] binaryBody, String textBody) {
        if (binaryBody != null) {
            return binaryBody;
        }
        return textBody == null || "GET".equals(method) || "HEAD".equals(method)
                ? null : textBody.getBytes(StandardCharsets.UTF_8);
    }

    /** A failed audit write is logged and does not fail the run; the attempt is still in the application log. */
    private void auditPrivateTarget(OutboundUrlPolicy.Target target, String method, OutboundUrlPolicy.Scope scope) {
        if (target.privateRule() == null) {
            return;
        }
        log.info("Allowed private target {}:{} ({}) for environment {} by rule {}", target.host(), target.port(),
                target.address() == null ? "unresolved" : target.address().getHostAddress(), scope.environmentId(),
                target.privateRule());
        if (auditService == null) {
            return;
        }
        try {
            auditService.recordPrivateTargetAllowed(scope.environmentId(), scope.environmentName(), target.host(),
                    target.port(), target.address() == null ? null : target.address().getHostAddress(), method,
                    target.privateRule());
        } catch (RuntimeException exception) {
            log.warn("Could not write the audit entry for private target {}:{}: {}", target.host(), target.port(),
                    exception.toString());
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static RunTestResult withRedirects(RunTestResult result, List<String> hops) {
        List<AssertionCheck> checks = new ArrayList<>(result.assertions() == null ? List.of() : result.assertions());
        checks.add(new AssertionCheck("redirects", null, "follow (up to " + MAX_REDIRECTS + ")",
                String.join(", ", hops), true));
        return result.withAssertions(List.copyOf(checks));
    }

    public RunTestResult skipped(StudioTestCase testCase) {
        return skipped(testCase, "Case is disabled (enabled=N)");
    }

    public RunTestResult skipped(StudioTestCase testCase, String reason) {
        String url = "";
        try {
            url = buildUrl(testCase.getBaseUrl(), testCase.getEndpoint(), copyMap(testCase.getQueryParams()));
        } catch (RuntimeException ignored) {
            url = StringUtils.defaultString(testCase.getEndpoint());
        }
        return new RunTestResult(
                false,
                true,
                StringUtils.defaultIfBlank(reason, "Case is disabled (enabled=N)"),
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
                null,
                Map.of()
        );
    }

    private RunTestResult evaluate(StudioTestCase testCase, String url, Map<String, String> requestHeaders,
                                   int actualStatus, Map<String, List<String>> rawHeaders, String body,
                                   String downloadPath, byte[] rawBody, long elapsedMs, long maxMs, String error,
                                   Map<String, String> variables) {
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
        Map<String, String> extracted = new LinkedHashMap<>();
        boolean fileResponse = "FILE".equalsIgnoreCase(StringUtils.trimToEmpty(testCase.getResponseType()));
        if (error == null) {
            assertions.addAll(HeaderAssertions.checks(testCase.getJsonPathAssertions(), rawHeaders));
        }
        if (error == null && fileResponse) {
            AssertionCheck download = CiResponseChecks.download(
                    testCase.getResponseType(), testCase.getExpectedResponse(), rawBody, downloadPath);
            if (download != null) {
                assertions.add(download);
            }
        } else if (error == null) {
            assertions.addAll(jsonPathChecks(testCase.getJsonPathAssertions(), body));
            AssertionCheck expectedBody = expectedBodyCheck(testCase, body);
            if (expectedBody != null) {
                assertions.add(expectedBody);
            }
            AssertionCheck schema = CiResponseChecks.schema(testCase.getResponseJsonSchema(), body);
            if (schema != null) {
                assertions.add(schema);
            }
            Map<String, String> extractedJson = new LinkedHashMap<>();
            assertions.addAll(CaseVariables.extractInto(extracted, extractedJson, testCase.getExtracts(), body,
                    rawHeaders));
            CaseVariables.putExtracted(variables, extracted, extractedJson);
        }

        boolean assertionsPassed = assertions.stream().allMatch(AssertionCheck::passed);
        boolean passed = error == null && statusMatched && !timedOut && assertionsPassed;
        log.info("HTTP {} {} -> {} expected {} passed={} {}ms case={}",
                StringUtils.defaultIfBlank(testCase.getMethod(), "GET"),
                urlForLog(url),
                actualStatus,
                expectedStatus,
                passed,
                elapsedMs,
                testCase.getId());
        log.debug("Case {} checks={} timedOut={} error={}",
                testCase.getId(), assertions.size(), timedOut, error);
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
                truncate(body),
                flattenHeaders(rawHeaders),
                requestHeaders,
                assertions,
                error,
                downloadPath,
                Map.copyOf(extracted)
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
            if (HeaderAssertions.isTransport(entry.getKey())) {
                continue;
            }
            String want = StringUtils.defaultString(entry.getValue());
            boolean xpath = XPathEvaluator.isXPath(entry.getKey());
            JsonPathEvaluator.Check check = xpath
                    ? XPathEvaluator.check(body, entry.getKey(), want)
                    : JsonPathEvaluator.check(root, entry.getKey(), want);
            String actual = check.actual();
            boolean match = check.passed();
            checks.add(new AssertionCheck(xpath ? "xpath" : "jsonPath", entry.getKey(), JsonPathEvaluator.describe(want),
                    actual, match));
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
                null,
                Map.of()
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
        JsonBodyMatcher.Mode mode = JsonBodyMatcher.mode(testCase.getResponseMatch());
        String difference = null;
        boolean match;
        JsonElement expectedJson = parseJsonDocument(expected);
        JsonElement actualJson = expectedJson == null ? null : parseJsonDocument(actual);
        List<String> ignored = JsonBodyMatcher.ignorePaths(testCase.getIgnoreFields());
        if (expectedJson != null && actualJson != null) {
            try {
                difference = JsonBodyMatcher.difference(JsonPathEvaluator.withoutPaths(expectedJson, ignored),
                        JsonPathEvaluator.withoutPaths(actualJson, ignored), mode);
            } catch (IllegalArgumentException invalidIgnorePath) {
                difference = invalidIgnorePath.getMessage();
            }
            match = difference == null;
        } else {
            match = XmlPayloadSupport.xmlEquals(expected, actual, testCase.getResponseJsonSchema())
                    || expected.trim().equals(actual.trim());
        }
        // path carries the compare mode and, on a JSON mismatch, where the bodies first differ.
        String detail = mode == JsonBodyMatcher.Mode.STRICT ? null : mode.label();
        if (expectedJson != null && !ignored.isEmpty()) {
            String note = ignored.size() == 1 ? "ignoring " + ignored.get(0) : "ignoring " + ignored.size() + " fields";
            detail = detail == null ? note : detail + ", " + note;
        }
        if (difference != null) {
            detail = detail == null ? difference : detail + " · " + difference;
        }
        return new AssertionCheck("expectedResponse", detail, truncate(expected.trim()), truncate(actual), match);
    }

    /** JSON object or array, or null when the text is not one (plain text and XML bodies). */
    private static JsonElement parseJsonDocument(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            return element.isJsonObject() || element.isJsonArray() ? element : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Applies {@code auth}; returns it when the bearer token came from the OAuth token cache, else null. */
    private RunAuth applyAuth(Map<String, String> headers, Map<String, String> query, Map<String, String> cookies,
                              RunAuth auth, OutboundUrlPolicy.Scope scope) {
        if (auth == null) {
            return null;
        }
        String type = auth.normalizedType();
        if (RunAuth.NONE.equals(type)) {
            return null;
        }
        if (RunAuth.BASIC.equals(type)) {
            if (StringUtils.isBlank(auth.username())) {
                throw new IllegalArgumentException("Username is required for credentials auth");
            }
            String token = Base64.getEncoder().encodeToString(
                    (auth.username() + ":" + StringUtils.defaultString(auth.password()))
                            .getBytes(StandardCharsets.UTF_8));
            HttpPayloadSupport.putHeader(headers, "Authorization", "Basic " + token);
            return null;
        }
        if (RunAuth.BEARER.equals(type) || (RunAuth.OAUTH2.equals(type) && StringUtils.isNotBlank(auth.token()))) {
            applyBearer(headers, auth.token());
            return null;
        }
        if (RunAuth.OAUTH2.equals(type)) {
            validateOAuth(auth, scope);
            applyBearer(headers, oauthTokens.token(auth, setup -> fetchOAuthToken(setup, scope)));
            return auth;
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
        return null;
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

    /** Checks an OAuth setup before the token cache is consulted, so a bad setup fails even with a cached token. */
    private void validateOAuth(RunAuth auth, OutboundUrlPolicy.Scope scope) {
        if (StringUtils.isBlank(auth.tokenUrl())) {
            throw new IllegalArgumentException("tokenUrl is required for OAuth2, or paste a bearer token");
        }
        outboundUrlPolicy.check(auth.tokenUrl().trim(), scope);
    }

    private OAuthTokenCache.Fetched fetchOAuthToken(RunAuth auth, OutboundUrlPolicy.Scope scope) {
        if (StringUtils.isBlank(auth.tokenUrl())) {
            throw new IllegalArgumentException("tokenUrl is required for OAuth2, or paste a bearer token");
        }
        OutboundUrlPolicy.Target target = outboundUrlPolicy.check(auth.tokenUrl().trim(), scope);
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
        if (StringUtils.isNotBlank(auth.scope())) {
            form.append("&scope=").append(java.net.URLEncoder.encode(auth.scope().trim(), StandardCharsets.UTF_8));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(auth.tokenUrl().trim()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8))
                    .build();
            auditPrivateTarget(target, "POST", scope);
            HttpResponse<byte[]> response = networkClients.channel(null, null)
                    .send(request, form.toString().getBytes(StandardCharsets.UTF_8), target);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("OAuth token request failed HTTP " + response.statusCode());
            }
            JsonElement parsed = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("access_token")) {
                throw new IllegalArgumentException("OAuth token response did not include access_token");
            }
            var token = parsed.getAsJsonObject();
            Long expiresIn = null;
            if (token.has("expires_in") && token.get("expires_in").isJsonPrimitive()) {
                try {
                    expiresIn = Long.parseLong(token.get("expires_in").getAsString().trim());
                } catch (NumberFormatException ignored) {
                    // Treated as unknown lifetime.
                }
            }
            return new OAuthTokenCache.Fetched(token.get("access_token").getAsString(), expiresIn);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("OAuth token request interrupted");
        } catch (Exception exception) {
            log.warn("OAuth token request failed", exception);
            throw new IllegalArgumentException("OAuth token request failed");
        }
    }

    private String requestBody(StudioTestCase testCase) {
        if (testCase.getRequestBody() == null || NA.equalsIgnoreCase(StringUtils.trimToEmpty(testCase.getContentType()))) {
            return null;
        }
        if (HttpPayloadSupport.isFormUrlEncoded(testCase.getContentType())) {
            return HttpPayloadSupport.formUrlEncoded(testCase.getRequestBody());
        }
        if (XmlPayloadSupport.isXml(testCase.getContentType())) {
            return XmlPayloadSupport.toXml(testCase.getRequestBody(), Map.of());
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

    /** Query strings can carry tokens, so logs keep only scheme, host, and path. */
    private static String urlForLog(String url) {
        if (url == null) {
            return "";
        }
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    static String buildUrl(String baseUrl, String endpoint, Map<String, String> query) {
        String base = StringUtils.trimToEmpty(baseUrl).replaceAll("/+$", "");
        String path = StringUtils.trimToEmpty(endpoint);
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return preferHttps(HttpPayloadSupport.appendQuery(path, query));
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
        return preferHttps(HttpPayloadSupport.appendQuery(base + path, query));
    }

    static String preferHttps(String url) {
        if (url == null || !url.startsWith("http://")) {
            return url;
        }
        String rest = url.substring("http://".length());
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        if (host.endsWith(".run.app")) {
            return "https://" + rest;
        }
        return url;
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static Map<String, String> filled(Map<String, String> source) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((name, value) -> {
                if (StringUtils.isNotBlank(name) && StringUtils.isNotBlank(value)) {
                    copy.put(name, value);
                }
            });
        }
        return copy;
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
        Path dir = Path.of(StringUtils.defaultIfBlank(properties.getDefaultOutputDir(), "./studio-output"))
                .resolve("downloads");
        try {
            Files.createDirectories(dir);
            String name = downloadFileName(testCase, contentType, disposition);
            Path file = dir.resolve(name);
            Files.write(file, bytes);
            return file.getFileName().toString();
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

    static Charset charsetOf(String contentType) {
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

    private String truncate(String body) {
        int max = properties == null ? DEFAULT_MAX_BODY_CHARS : Math.max(1_000, properties.getRun().getMaxResponseChars());
        if (body == null || body.length() <= max) {
            return body;
        }
        return body.substring(0, max) + "…";
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
