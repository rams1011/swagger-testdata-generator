package com.tx.api.test.generator.studio.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.tx.api.test.generator.studio.collection.CollectionFormatException;
import com.tx.api.test.generator.studio.collection.StudioEnvironmentNetwork;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkAndAuthRunTest {

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<String> seen = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private StudioProperties properties;
    private TestRunService service;
    private int port;

    @BeforeEach
    void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        servers.add(server);
        port = server.getAddress().getPort();
        properties = new StudioProperties();
        properties.getRun().setAllowPrivateNetworks(true);
        service = new TestRunService(null, properties, new OutboundUrlPolicy(properties));
    }

    @AfterEach
    void stop() {
        servers.forEach(server -> server.stop(0));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        seen.add(exchange.getRequestMethod() + " " + path + " auth=" + auth + " body="
                + new String(requestBody, StandardCharsets.UTF_8));
        switch (path) {
            case "/old" -> redirect(exchange, 302, "/new");
            case "/post-redirect" -> redirect(exchange, 303, "/new");
            case "/keep" -> redirect(exchange, 307, "/echo");
            case "/loop" -> redirect(exchange, 302, "/loop");
            case "/away" -> redirect(exchange, 302, "http://localhost:" + port + "/new");
            case "/slow" -> {
                try {
                    Thread.sleep(2_500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, "{}");
            }
            case "/token" -> {
                int call = tokenCalls.incrementAndGet();
                respond(exchange, 200, "{\"access_token\":\"tok-" + call + "\",\"expires_in\":3600,\"scope\":\""
                        + new String(requestBody, StandardCharsets.UTF_8).contains("scope=roles.read") + "\"}");
            }
            case "/secured" -> respond(exchange, "Bearer tok-1".equals(auth) && tokenCalls.get() == 1 ? 200 : 401, "{}");
            default -> respond(exchange, 200, "{\"path\":\"" + path + "\"}");
        }
    }

    private static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void redirectsAreNotFollowedUnlessTheEnvironmentSaysSo() {
        RunTestResult plain = service.execute(get("/old", 302), null, new LinkedHashMap<>(), null);
        assertTrue(plain.passed(), String.valueOf(plain.assertions()));

        RunTestResult followed = service.execute(get("/old", 200), null, new LinkedHashMap<>(), network(true, null));
        assertTrue(followed.passed(), String.valueOf(followed.assertions()));
        assertTrue(followed.responseBody().contains("/new"));
        AssertionCheck hops = check(followed, "redirects");
        assertTrue(hops.actual().startsWith("302 → http://127.0.0.1:" + port + "/new"), hops.actual());
    }

    @Test
    void see303TurnsAPostIntoAGetAnd307KeepsMethodAndBody() {
        StudioTestCase post = get("/post-redirect", 200);
        post.setMethod("POST");
        post.setContentType("application/json");
        post.setRequestBody(Map.of("a", 1));
        service.execute(post, null, new LinkedHashMap<>(), network(true, null));
        assertEquals("GET /new auth=null body=", seen.get(seen.size() - 1));

        post.setEndpoint("/keep");
        service.execute(post, null, new LinkedHashMap<>(), network(true, null));
        assertEquals("POST /echo auth=null body={\"a\":1}", seen.get(seen.size() - 1));
    }

    @Test
    void redirectLoopsStopAndForeignHostsLoseTheAuthorizationHeader() {
        RunTestResult loop = service.execute(get("/loop", 200), null, new LinkedHashMap<>(), network(true, null));
        assertFalse(loop.passed());
        assertTrue(loop.error().contains("Stopped after 10 redirects"), loop.error());

        RunAuth bearer = new RunAuth("BEARER", null, null, "secret-token", null, null, null, null, null, null, null);
        service.execute(get("/away", 200), bearer, new LinkedHashMap<>(), network(true, null));
        assertEquals("GET /away auth=Bearer secret-token body=", seen.get(seen.size() - 2));
        assertEquals("GET /new auth=null body=", seen.get(seen.size() - 1));
    }

    @Test
    void redirectTargetsPassTheOutboundPolicy() {
        properties.getRun().setAllowHosts("127.0.0.1");
        RunTestResult result = service.execute(get("/away", 200), null, new LinkedHashMap<>(), network(true, null));
        assertFalse(result.passed());
        assertTrue(result.error().contains("not on the allow list"), result.error());
    }

    @Test
    void environmentTimeoutOverridesTheCaseTimeAndIsCapped() {
        StudioTestCase slow = get("/slow", 200);
        slow.setMaxResponseTimeMs(10_000);
        long started = System.nanoTime();
        RunTestResult result = service.execute(slow, null, new LinkedHashMap<>(),
                new StudioEnvironmentNetwork(null, null, null, null, 1_000L));
        assertTrue(result.timedOut());
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_400);

        properties.getRun().setMaxRequestTimeoutMs(20_000);
        assertEquals(20_000, service.timeoutMs(3_000, new StudioEnvironmentNetwork(null, null, null, null, 900_000L)));
        assertEquals(10_000, service.timeoutMs(3_000, null));
        assertEquals(20_000, service.timeoutMs(60_000, null));
    }

    @Test
    void requestsGoThroughTheProxy() throws IOException {
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> proxied = Collections.synchronizedList(new ArrayList<>());
        proxy.createContext("/", exchange -> {
            proxied.add(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"via\":\"proxy\"}");
        });
        proxy.start();
        servers.add(proxy);
        StudioTestCase testCase = get("/through", 200);
        testCase.setBaseUrl("http://api.example.test");
        RunTestResult result = service.execute(testCase, null, new LinkedHashMap<>(),
                new StudioEnvironmentNetwork(null, null, null, "http://127.0.0.1:" + proxy.getAddress().getPort(), null));
        assertTrue(result.passed(), result.error() + " " + result.assertions());
        assertEquals(List.of("http://api.example.test/through"), proxied);
    }

    @Test
    void selfSignedServersNeedTheCaOrVerifyOff() throws Exception {
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverTls()));
        https.createContext("/", exchange -> respond(exchange, 200, "{\"tls\":true}"));
        https.start();
        servers.add(https);
        StudioTestCase testCase = get("/secure", 200);
        testCase.setBaseUrl("https://127.0.0.1:" + https.getAddress().getPort());

        RunTestResult untrusted = service.execute(testCase, null, new LinkedHashMap<>(), null);
        assertFalse(untrusted.passed());
        assertTrue(untrusted.error().startsWith("TLS handshake failed"), untrusted.error());

        RunTestResult insecure = service.execute(testCase, null, new LinkedHashMap<>(),
                new StudioEnvironmentNetwork(null, false, null, null, null));
        assertTrue(insecure.passed(), insecure.error());

        RunTestResult trusted = service.execute(testCase, null, new LinkedHashMap<>(),
                new StudioEnvironmentNetwork(null, null, pem(), null, null));
        assertTrue(trusted.passed(), trusted.error());

        testCase.setBaseUrl("https://localhost:" + https.getAddress().getPort());
        assertTrue(service.execute(testCase, null, new LinkedHashMap<>(),
                new StudioEnvironmentNetwork(null, null, pem(), null, null)).passed());
    }

    @Test
    void oauthTokensAreCachedAndA401DropsThem() {
        RunAuth oauth = new RunAuth("OAUTH2", null, null, null, null, null, null, "client", "s3cret",
                "http://127.0.0.1:" + port + "/token", "client_credentials", "roles.read");
        StudioTestCase secured = get("/secured", 200);
        assertTrue(service.execute(secured, oauth, new LinkedHashMap<>(), null).passed());
        assertTrue(service.execute(secured, oauth, new LinkedHashMap<>(), null).passed());
        assertEquals(1, tokenCalls.get());
        assertTrue(seen.stream().anyMatch(line -> line.startsWith("POST /token") && line.contains("scope=roles.read")));

        tokenCalls.set(5);
        assertFalse(service.execute(secured, oauth, new LinkedHashMap<>(), null).passed());
        service.execute(secured, oauth, new LinkedHashMap<>(), null);
        assertEquals(6, tokenCalls.get());
    }

    @Test
    void caseAuthOverridesTheRunAuthAndReadsVariables() {
        RunAuth runBearer = new RunAuth("BEARER", null, null, "env-token", null, null, null, null, null, null, null);
        StudioTestCase publicCase = get("/public", 200);
        publicCase.setAuth(CaseAuth.normalize(new RunAuth("NONE", null, null, null, null, null, null, null, null, null,
                null)));
        service.execute(publicCase, runBearer, new LinkedHashMap<>(), null);
        assertEquals("GET /public auth=null body=", seen.get(seen.size() - 1));

        StudioTestCase admin = get("/admin", 200);
        admin.setAuth(CaseAuth.normalize(new RunAuth("BEARER", null, null, "{{adminToken}}", null, null, null, null,
                null, null, null)));
        assertTrue(CaseVariables.placeholderNames(admin).contains("adminToken"));
        service.execute(admin, runBearer, new LinkedHashMap<>(Map.of("adminToken", "admin-123")), null);
        assertEquals("GET /admin auth=Bearer admin-123 body=", seen.get(seen.size() - 1));

        StudioTestCase inherit = get("/inherit", 200);
        inherit.setAuth(CaseAuth.normalize(new RunAuth("INHERIT", null, null, null, null, null, null, null, null, null,
                null)));
        assertNull(inherit.getAuth());
        service.execute(inherit, runBearer, new LinkedHashMap<>(), null);
        assertEquals("GET /inherit auth=Bearer env-token body=", seen.get(seen.size() - 1));
    }

    @Test
    void caseAuthNeverStoresSecretValues() {
        IllegalArgumentException literal = assertThrows(IllegalArgumentException.class, () -> CaseAuth.normalize(
                new RunAuth("BEARER", null, null, "abc123", null, null, null, null, null, null, null)));
        assertTrue(literal.getMessage().contains("must be a variable"), literal.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CaseAuth.normalize(
                new RunAuth("BASIC", "user", "Bearer {{p}}", null, null, null, null, null, null, null, null)));
        RunAuth basic = CaseAuth.normalize(new RunAuth("basic", " {{user}} ", "{{password}}", null, null, null, null,
                null, null, null, null));
        assertEquals("BASIC", basic.type());
        assertEquals("{{user}}", basic.username());
        assertEquals(basic, CaseAuth.fromJson(CaseAuth.toJson(basic)));
    }

    @Test
    void networkSettingsAreValidated() {
        assertNull(StudioEnvironmentNetwork.normalize(new StudioEnvironmentNetwork(false, true, " ", "", 0L)));
        StudioEnvironmentNetwork normalized = StudioEnvironmentNetwork.normalize(
                new StudioEnvironmentNetwork(true, false, "\n" + pem() + "\n", " proxy.corp:3128 ", 120_000L));
        assertEquals(Boolean.TRUE, normalized.followRedirects());
        assertEquals(Boolean.FALSE, normalized.verifySsl());
        assertEquals("proxy.corp:3128", normalized.proxyUrl());
        assertEquals(3128, StudioEnvironmentNetwork.proxyPort(StudioEnvironmentNetwork.proxyUri(normalized.proxyUrl())));
        assertThrows(CollectionFormatException.class, () -> StudioEnvironmentNetwork.normalize(
                new StudioEnvironmentNetwork(null, null, null, "http://user:pw@proxy:8080", null)));
        assertThrows(CollectionFormatException.class, () -> StudioEnvironmentNetwork.normalize(
                new StudioEnvironmentNetwork(null, null, "not a cert", null, null)));
        assertThrows(CollectionFormatException.class, () -> StudioEnvironmentNetwork.normalize(
                new StudioEnvironmentNetwork(null, null, null, "ftp://proxy:21", null)));
        assertThrows(CollectionFormatException.class, () -> StudioEnvironmentNetwork.normalize(
                new StudioEnvironmentNetwork(null, null, null, null, 500L)));
    }

    @Test
    void environmentsKeepTheirNetworkSettingsThroughUpdatesAndJson() {
        var network = new StudioEnvironmentNetwork(true, false, null, "http://proxy.corp:3128", 90_000L);
        var created = com.tx.api.test.generator.studio.collection.EnvironmentMapper.fromInputs(
                "int", "https://int.example.com", "BEARER", null, network);
        var json = com.tx.api.test.generator.studio.collection.CollectionDocuments.toJson(created);
        assertTrue(json.contains("\"network\""), json);
        var parsed = com.tx.api.test.generator.studio.collection.CollectionDocuments.parseEnvironment(json);
        assertEquals(network, parsed.network());
        var renamed = com.tx.api.test.generator.studio.collection.EnvironmentMapper.merge(
                parsed, "int2", null, null, null, null);
        assertEquals(network, renamed.network());
        var cleared = com.tx.api.test.generator.studio.collection.EnvironmentMapper.merge(
                parsed, "int2", null, null, null, new StudioEnvironmentNetwork(false, true, "", "", null));
        assertNull(cleared.network());
    }

    @Test
    void sendFailuresSayWhy() throws IOException {
        int closedPort;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        StudioTestCase refused = get("/x", 200);
        refused.setBaseUrl("http://127.0.0.1:" + closedPort);
        String error = service.execute(refused, null, new LinkedHashMap<>(), null).error();
        assertTrue(error.startsWith("Connection refused by 127.0.0.1:" + closedPort), error);

        assertEquals("Host not found: api.example.test. Check the base URL and the selected environment.",
                TestRunService.sendFailure(new java.net.ConnectException("x") {{
                    initCause(new java.nio.channels.UnresolvedAddressException());
                }}, "https://api.example.test/orders?token=secret", null));
        assertTrue(TestRunService.sendFailure(new java.io.IOException("Connection reset"), "http://h:8080/a", null)
                .contains("expects HTTPS instead of HTTP"));
        assertEquals("The proxy refused the connection to h:443 (HTTP 403). Check the environment's proxy "
                + "setting or the server's proxy rules.",
                TestRunService.sendFailure(new java.io.IOException("Tunnel failed, got: 403"), "https://h/a", null));
        assertEquals("The request to h:443 could not be sent.",
                TestRunService.sendFailure(new IllegalStateException("odd"), "https://h/a", null));
    }

    @Test
    void blankQueryHeadersAndCookiesAreLeftOutLikeUnsetOptionalParameters() {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        servers.get(0).createContext("/inspect", exchange -> {
            var headers = exchange.getRequestHeaders();
            received.add("query=" + exchange.getRequestURI().getRawQuery()
                    + " blank=" + headers.containsKey("X-Blank")
                    + " filled=" + headers.getFirst("X-Filled")
                    + " cookie=" + headers.getFirst("Cookie"));
            respond(exchange, 200, "{}");
        });
        StudioTestCase testCase = get("/inspect", 200);
        testCase.setQueryParams(new LinkedHashMap<>(Map.of("level", "", "app", "{{app}}")));
        testCase.setHeaders(new LinkedHashMap<>(Map.of("X-Blank", " ", "X-Filled", "yes")));
        testCase.setCookieParams(new LinkedHashMap<>(Map.of("session", "s1", "empty", "{{none}}")));

        RunTestResult result = service.execute(testCase, null, new LinkedHashMap<>(Map.of("app", "web", "none", "")));

        assertTrue(result.passed(), result.error());
        assertEquals(List.of("query=app=web blank=false filled=yes cookie=session=s1"), received);
    }

    @Test
    void eachPathValueIsEncodedOnItsOwnAndAMissingOneIsNamed() {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        servers.get(0).createContext("/paths", exchange -> {
            received.add(exchange.getRequestURI().getRawPath());
            respond(exchange, 200, "{}");
        });
        StudioTestCase testCase = get("/paths", 200);
        testCase.setRawPath("/paths/{id}/notes/{note}");
        testCase.setPathParams(new LinkedHashMap<>(Map.of("id", "{{itemId}}", "note", "a/b c?#%2F")));

        RunTestResult result = service.execute(testCase, null, new LinkedHashMap<>(Map.of("itemId", "café 1")));

        assertTrue(result.passed(), result.error());
        // the same bytes api-automation sends: a "/" "?" "#" or space stays in its segment, an %2F isn't doubled
        assertEquals(List.of("/paths/caf%C3%A9%201/notes/a%2Fb%20c%3F%23%2F"), received);

        StudioTestCase missing = get("/paths", 200);
        missing.setRawPath("/paths/{id}");
        missing.setPathParams(new LinkedHashMap<>(Map.of("id", " ")));
        RunTestResult missingResult = service.execute(missing, null, new LinkedHashMap<>());
        assertFalse(missingResult.passed());
        assertTrue(missingResult.error().startsWith("Path parameter 'id' has no value"), missingResult.error());
    }

    @Test
    void aHeaderTheClientSetsOrAMissingCredentialFailsTheCaseInsteadOfThrowing() {
        StudioTestCase host = get("/echo", 200);
        host.setHeaders(new LinkedHashMap<>(Map.of("Host", "other.example.com")));
        RunTestResult hostResult = service.execute(host, null, new LinkedHashMap<>());
        assertFalse(hostResult.passed());
        assertTrue(hostResult.error().startsWith("Header 'Host' can't be set by a test"), hostResult.error());

        StudioTestCase badName = get("/echo", 200);
        badName.setHeaders(new LinkedHashMap<>(Map.of("Bad Name", "x")));
        assertTrue(service.execute(badName, null, new LinkedHashMap<>()).error().contains("isn't a valid header name"));

        RunAuth noKey = new RunAuth("API_KEY", null, null, null, null, null, null, null, null, null, null);
        RunTestResult keyResult = service.execute(get("/echo", 200), noKey, new LinkedHashMap<>());
        assertFalse(keyResult.passed());
        assertEquals("API key is required", keyResult.error());
        assertTrue(seen.isEmpty(), "nothing is sent: " + seen);
    }

    private StudioTestCase get(String path, int status) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setTestName("case " + path);
        testCase.setMethod("GET");
        testCase.setBaseUrl("http://127.0.0.1:" + port);
        testCase.setEndpoint(path);
        testCase.setStatusCode(status);
        testCase.setResponseType("JSON");
        testCase.setMaxResponseTimeMs(5_000);
        return testCase;
    }

    private static StudioEnvironmentNetwork network(Boolean follow, Boolean verify) {
        return new StudioEnvironmentNetwork(follow, verify, null, null, null);
    }

    private static AssertionCheck check(RunTestResult result, String name) {
        return result.assertions().stream().filter(item -> name.equals(item.name())).findFirst().orElseThrow();
    }

    static String pem() {
        try (InputStream in = NetworkAndAuthRunTest.class.getResourceAsStream("/tls/studio-test.pem")) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static SSLContext serverTls() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = NetworkAndAuthRunTest.class.getResourceAsStream("/tls/studio-test.p12")) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }
}
