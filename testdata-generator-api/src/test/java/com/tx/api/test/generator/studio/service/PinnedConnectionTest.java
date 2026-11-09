package com.tx.api.test.generator.studio.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.tx.api.test.generator.studio.audit.AuditAction;
import com.tx.api.test.generator.studio.audit.AuditResourceType;
import com.tx.api.test.generator.studio.audit.StudioAuditService;
import com.tx.api.test.generator.studio.collection.StudioEnvironmentNetwork;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Real connections to a local server, under names only the test's resolver knows. */
class PinnedConnectionTest {

    private static final String HOST = "pinned.example.test";
    private static final OutboundUrlPolicy.Scope STAGING =
            new OutboundUrlPolicy.Scope("11111111-1111-1111-1111-111111111111", "Staging");

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<String> hostHeaders = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sniNames = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger lookups = new AtomicInteger();
    private final Map<String, List<String>> dns = new LinkedHashMap<>();
    private final StudioStorage storage = mock(StudioStorage.class);
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
        allow("127.0.0.1:" + port);
        dns.put(HOST, List.of("127.0.0.1"));
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties, null, host -> {
            lookups.incrementAndGet();
            List<String> answers = dns.get(host);
            if (answers == null) {
                throw new UnknownHostException(host);
            }
            List<InetAddress> resolved = new ArrayList<>();
            for (String answer : answers) {
                resolved.add(InetAddress.getByName(answer));
            }
            return resolved;
        });
        service = new TestRunService(null, properties, policy, new StudioAuditService(storage));
    }

    @AfterEach
    void stop() {
        servers.forEach(server -> server.stop(0));
    }

    private void allow(String... entries) {
        properties.getRun().setPrivateTargetAllowlist(Map.of(STAGING.environmentId(), List.of(entries)));
    }

    private void handle(HttpExchange exchange) throws IOException {
        hostHeaders.add(exchange.getRequestHeaders().getFirst("Host"));
        exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/to-metadata")) {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        if (path.startsWith("/to-pinned")) {
            exchange.getResponseHeaders().add("Location", "http://" + HOST + ":" + port + "/done");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        respond(exchange);
    }

    private static void respond(HttpExchange exchange) throws IOException {
        byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void connectsToTheValidatedAddressWithTheOriginalHostHeader() {
        RunTestResult result = run("http://" + HOST + ":" + port, "/hello", null);

        assertTrue(result.passed(), result.error() + " " + result.assertions());
        assertEquals(List.of(HOST + ":" + port), hostHeaders);
        assertEquals(1, lookups.get());
    }

    @Test
    void aSecondDnsAnswerCannotChangeTheConnection() {
        // The answer is read once, and the next lookup would fail. The request still reaches the validated address.
        dns.put(HOST, new ArrayList<>(List.of("127.0.0.1")) {
            private int reads;

            @Override
            public java.util.Iterator<String> iterator() {
                if (reads++ > 0) {
                    throw new AssertionError("The host was resolved a second time");
                }
                return super.iterator();
            }
        });

        RunTestResult result = run("http://" + HOST + ":" + port, "/hello", null);

        assertTrue(result.passed(), result.error() + " " + result.assertions());
        assertEquals(1, lookups.get());
    }

    @Test
    void aRebindingHostIsBlockedBeforeAnyConnection() {
        dns.put(HOST, List.of("93.184.216.34", "127.0.0.1"));
        allow();

        RunTestResult result = run("http://" + HOST + ":" + port, "/hello", null);

        assertFalse(result.passed());
        assertTrue(result.error().contains("is not allowed (environment \"Staging\")"), result.error());
        assertTrue(hostHeaders.isEmpty());
    }

    @Test
    void tlsUsesTheOriginalNameForSniAndTheCertificateCheckWhileConnectingToThePinnedAddress(@TempDir Path dir)
            throws Exception {
        String pem = startTlsServer(dir, HOST);
        allow("127.0.0.1:" + tlsPort);
        StudioEnvironmentNetwork trust = new StudioEnvironmentNetwork(null, null, pem, null, null);

        RunTestResult result = run("https://" + HOST + ":" + tlsPort, "/secure", trust);

        assertTrue(result.passed(), result.error() + " " + result.assertions());
        assertEquals(List.of(HOST), sniNames);
        assertEquals(List.of(HOST + ":" + tlsPort), hostHeaders);
    }

    @Test
    void theCertificateMustMatchTheOriginalNameNotThePinnedAddress(@TempDir Path dir) throws Exception {
        String pem = startTlsServer(dir, HOST);
        allow("127.0.0.1:" + tlsPort);
        dns.put("other.example.test", List.of("127.0.0.1"));
        StudioEnvironmentNetwork trust = new StudioEnvironmentNetwork(null, null, pem, null, null);

        RunTestResult result = run("https://other.example.test:" + tlsPort, "/secure", trust);

        assertFalse(result.passed());
        assertTrue(result.error().startsWith("TLS handshake failed"), result.error());
        assertTrue(hostHeaders.isEmpty());
    }

    @Test
    void verifySslOffStillSendsTheOriginalNameAndConnectsToThePinnedAddress(@TempDir Path dir) throws Exception {
        startTlsServer(dir, "something-else.example.test");
        allow("127.0.0.1:" + tlsPort);

        RunTestResult result = run("https://" + HOST + ":" + tlsPort, "/secure",
                new StudioEnvironmentNetwork(null, false, null, null, null));

        assertTrue(result.passed(), result.error() + " " + result.assertions());
        assertEquals(List.of(HOST), sniNames);
    }

    @Test
    void aRedirectIsNotFollowedByDefault() {
        StudioTestCase testCase = testCase("http://127.0.0.1:" + port, "/to-metadata", 302);
        RunTestResult result = service.execute(testCase, null, new LinkedHashMap<>(), null, STAGING);
        assertTrue(result.passed(), result.error() + " " + result.assertions());
        assertEquals(1, hostHeaders.size());
    }

    @Test
    void anOptedInRedirectToABlockedTargetStopsAndAnAllowedHopIsPinned() {
        StudioEnvironmentNetwork follow = new StudioEnvironmentNetwork(true, null, null, null, null);

        RunTestResult blocked = run("http://127.0.0.1:" + port, "/to-metadata", follow);
        assertFalse(blocked.passed());
        assertTrue(blocked.error().contains("cannot be added to the allowlist"), blocked.error());

        hostHeaders.clear();
        lookups.set(0);
        RunTestResult followed = run("http://127.0.0.1:" + port, "/to-pinned", follow);
        assertTrue(followed.passed(), followed.error() + " " + followed.assertions());
        assertEquals(List.of("127.0.0.1:" + port, HOST + ":" + port), hostHeaders);
        assertEquals(1, lookups.get());
    }

    @Test
    void anIpv6LiteralKeepsItsBracketsInTheHostHeader() throws IOException {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
        } catch (IOException noIpv6) {
            org.junit.jupiter.api.Assumptions.abort("IPv6 loopback is not available");
            return;
        }
        server.createContext("/", this::handle);
        server.start();
        servers.add(server);
        int v6Port = server.getAddress().getPort();
        allow("[::1]:" + v6Port);

        RunTestResult result = run("http://[::1]:" + v6Port, "/hello", null);

        assertTrue(result.passed(), result.error() + " " + result.assertions());
        assertEquals(List.of("[::1]:" + v6Port), hostHeaders);
    }

    @Test
    void aDirectRequestWithNoValidatedAddressIsRefusedWithoutALookup() {
        properties.getRun().setAllowPrivateNetworks(true);

        RunTestResult result = run("http://gone.example.test:" + port, "/hello", null);

        assertFalse(result.passed());
        assertEquals("Host not found: gone.example.test. Check the base URL and the selected environment.", result.error());
        assertEquals(1, lookups.get());
    }

    @Test
    void aRestrictedHostHeaderIsStillRefused() {
        StudioTestCase testCase = testCase("http://" + HOST + ":" + port, "/hello", 200);
        testCase.setHeaders(new LinkedHashMap<>(Map.of("Host", "attacker.example")));
        RunTestResult result = service.execute(testCase, null, new LinkedHashMap<>(), null, STAGING);
        assertFalse(result.passed());
        assertTrue(result.error().startsWith("Header 'Host' can't be set by a test"), result.error());
        assertTrue(hostHeaders.isEmpty());
    }

    @Test
    void headersThatTheJdkClientNeverSentAreNotAdded() {
        List<Map<String, List<String>>> seen = Collections.synchronizedList(new ArrayList<>());
        servers.get(0).removeContext("/");
        servers.get(0).createContext("/", exchange -> {
            seen.add(new LinkedHashMap<>(exchange.getRequestHeaders()));
            exchange.getRequestBody().readAllBytes();
            respond(exchange);
        });
        StudioTestCase post = testCase("http://" + HOST + ":" + port, "/hello", 200);
        post.setMethod("POST");
        post.setContentType("application/json");
        post.setRequestBody(Map.of("a", 1));

        assertTrue(service.execute(post, null, new LinkedHashMap<>(), null, STAGING).passed());

        Map<String, List<String>> headers = seen.get(0);
        assertFalse(headers.containsKey("Accept-encoding"), headers.toString());
        assertFalse(headers.containsKey("Expect"), headers.toString());
        assertFalse(headers.containsKey("Upgrade"), headers.toString());
        assertEquals(List.of("application/json"), headers.get("Content-type"));
    }

    // Audit

    @Test
    void aRequestToAnAllowedPrivateTargetIsAuditedWithoutItsQuery() {
        RunTestResult result = run("http://" + HOST + ":" + port, "/hello?token=secret", null);
        assertTrue(result.passed(), result.error() + " " + result.assertions());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(storage, times(1)).appendAuditEvent(any(), isNull(), eq(AuditResourceType.OUTBOUND_TARGET),
                eq(STAGING.environmentId()), eq(AuditAction.PRIVATE_TARGET_ALLOWED), any(), any(), detail.capture(),
                any(Instant.class));
        assertEquals("GET to private target " + HOST + ":" + port + " (127.0.0.1) in environment Staging, allowed by "
                + "127.0.0.1:" + port, detail.getValue());
        assertFalse(detail.getValue().contains("secret"));
    }

    @Test
    void publicAndBlockedTargetsAreNotAudited() {
        dns.put("public.example.test", List.of("93.184.216.34"));
        dns.put(HOST, List.of("10.9.9.9"));
        allow();
        run("http://" + HOST + ":" + port, "/hello", null);
        OutboundUrlPolicy.Target publicTarget = new OutboundUrlPolicy(properties, null,
                host -> List.of(InetAddress.getByName("93.184.216.34")))
                .check("http://public.example.test/", STAGING);
        assertEquals(null, publicTarget.privateRule());
        verify(storage, never()).appendAuditEvent(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aFailedAuditWriteDoesNotFailTheRun() {
        doThrow(new IllegalStateException("database down")).when(storage)
                .appendAuditEvent(any(), any(), any(), any(), any(), any(), any(), any(), any());
        RunTestResult result = run("http://" + HOST + ":" + port, "/hello", null);
        assertTrue(result.passed(), result.error() + " " + result.assertions());
    }

    private RunTestResult run(String baseUrl, String endpoint, StudioEnvironmentNetwork network) {
        return service.execute(testCase(baseUrl, endpoint, 200), null, new LinkedHashMap<>(), network, STAGING);
    }

    private static StudioTestCase testCase(String baseUrl, String endpoint, int status) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setTestName("case " + endpoint);
        testCase.setMethod("GET");
        testCase.setBaseUrl(baseUrl);
        testCase.setEndpoint(endpoint);
        testCase.setStatusCode(status);
        testCase.setResponseType("JSON");
        testCase.setMaxResponseTimeMs(5_000);
        return testCase;
    }

    private int tlsPort;

    /** Starts an HTTPS server with a new certificate for {@code certificateName}, and returns the certificate as PEM. */
    private String startTlsServer(Path dir, String certificateName) throws Exception {
        Path store = dir.resolve("server.p12");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=" + certificateName, "-ext", "SAN=dns:" + certificateName, "-ext", "BC:critical=ca:true",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit");
        Path pem = dir.resolve("server.pem");
        keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", store.toString(), "-storepass", "changeit",
                "-file", pem.toString());

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keys.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);

        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters ssl = context.getDefaultSSLParameters();
                ssl.setSNIMatchers(List.of(new SNIMatcher(0) {
                    @Override
                    public boolean matches(SNIServerName serverName) {
                        sniNames.add(new SNIHostName(serverName.getEncoded()).getAsciiName());
                        return true;
                    }
                }));
                parameters.setSSLParameters(ssl);
            }
        });
        https.createContext("/", this::handle);
        https.start();
        servers.add(https);
        tlsPort = https.getAddress().getPort();
        return Files.readString(pem, StandardCharsets.US_ASCII);
    }

    private static void keytool(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }
}
