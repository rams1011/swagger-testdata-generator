package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.TestSchema;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tx.api.test.generator.studio.audit.StudioAuditService;
import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AuditEventView;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.SpecSummary;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real downloads from a local server, under names only the test's resolver knows. */
class PinnedSpecImportTest {

    private static final String HOST = "specs.example.test";
    private static final String SPEC = "openapi: 3.0.0\ninfo: {title: Pets, version: '1'}\npaths:\n  /pets:\n    get:\n"
            + "      responses:\n        '200':\n          description: ok\n";

    private final List<String> hostHeaders = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger lookups = new AtomicInteger();
    private final Map<String, List<String>> dns = new LinkedHashMap<>();
    private DataSource dataSource;
    private JdbcStudioStorage storage;
    private StudioProperties properties;
    private SpecCatalogService catalog;
    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("spec_import_" + System.nanoTime()), "sa", "");
        h2.setDriverClassName(H2Database.DRIVER);
        dataSource = h2;
        TestSchema.migrate(dataSource);
        TenantContext.setSaasEnabled(false);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
        dns.put(HOST, List.of("127.0.0.1"));

        properties = new StudioProperties();
        properties.getRun().setAllowPrivateNetworks(true);
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
        storage = new JdbcStudioStorage(new JdbcTemplate(dataSource));
        catalog = new SpecCatalogService(storage, null, new StudioAuditService(storage), policy);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        TenantContext.clear();
        new JdbcTemplate(dataSource).execute("SHUTDOWN");
    }

    private void handle(HttpExchange exchange) throws IOException {
        hostHeaders.add(exchange.getRequestHeaders().getFirst("Host"));
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/redirect")) {
            exchange.getResponseHeaders().add("Location", "/openapi.yaml");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        byte[] bytes = (path.equals("/remote-ref")
                ? "openapi: 3.0.0\ninfo: {title: t, version: '1'}\npaths:\n  /a: {$ref: http://127.0.0.1:" + port + "/x}\n"
                : SPEC).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(path.equals("/missing") ? 404 : 200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private SpecSummary load(String location) {
        return catalog.load(new LoadSpecRequest(location, null, null, null, "Pets", null, null, null, null));
    }

    @Test
    void downloadsFromTheValidatedAddressWithTheOriginalHostHeader() {
        SpecSummary summary = load("http://" + HOST + ":" + port + "/openapi.yaml");

        assertEquals(1, summary.operationCount());
        assertEquals(List.of(HOST + ":" + port), hostHeaders);
        assertEquals(1, lookups.get());
    }

    @Test
    void aRebindingHostIsBlockedBeforeAnyConnection() {
        properties.getRun().setAllowPrivateNetworks(false);
        dns.put(HOST, List.of("93.184.216.34", "127.0.0.1"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> load("http://" + HOST + ":" + port + "/openapi.yaml"));

        assertEquals("Run target host " + HOST + " is not allowed (spec import). Ask an admin to allow it.",
                exception.getMessage());
        assertFalse(exception.getMessage().contains("127.0.0.1"));
        assertTrue(hostHeaders.isEmpty());
    }

    @Test
    void anEnvironmentsAllowlistDoesNotOpenSpecImport() {
        properties.getRun().setAllowPrivateNetworks(false);
        properties.getRun().setPrivateTargetAllowlist(Map.of(
                "11111111-1111-1111-1111-111111111111", List.of("127.0.0.1:" + port)));

        assertThrows(IllegalArgumentException.class, () -> load("http://127.0.0.1:" + port + "/openapi.yaml"));
        assertTrue(hostHeaders.isEmpty());
    }

    @Test
    void metadataStaysBlockedEvenWithTheDeprecatedFlag() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> load("http://169.254.169.254/openapi.yaml"));
        assertTrue(exception.getMessage().contains("cannot be added to the allowlist"), exception.getMessage());
    }

    @Test
    void aRedirectIsNotFollowed() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> load("http://" + HOST + ":" + port + "/redirect"));
        assertEquals("The spec could not be downloaded.", exception.getMessage());
        assertEquals(1, hostHeaders.size());
    }

    @Test
    void anErrorStatusIsReportedAsADownloadFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> load("http://" + HOST + ":" + port + "/missing"));
        assertEquals("The spec could not be downloaded.", exception.getMessage());
    }

    @Test
    void anUnresolvedHostIsRefusedWithoutASecondLookup() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> load("http://gone.example.test:" + port + "/openapi.yaml"));
        assertEquals("The spec could not be downloaded.", exception.getMessage());
        assertEquals(1, lookups.get());
        assertTrue(hostHeaders.isEmpty());
    }

    @Test
    void aDownloadedSpecCannotMakeTheServerFetchItsReferences() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> load("http://" + HOST + ":" + port + "/remote-ref"));
        assertTrue(exception.getMessage().contains("Remote or file references are not allowed"), exception.getMessage());
        assertEquals(1, hostHeaders.size());
    }

    @Test
    void reloadUsesThePinnedDownloadToo() {
        SpecSummary summary = load("http://" + HOST + ":" + port + "/openapi.yaml");
        hostHeaders.clear();
        lookups.set(0);

        catalog.reload(summary.specId(), new LoadSpecRequest("http://" + HOST + ":" + port + "/openapi.yaml",
                null, null, null, null, null, null, null, null));

        assertEquals(List.of(HOST + ":" + port), hostHeaders);
        assertEquals(1, lookups.get());
    }

    @Test
    void aDownloadFromAPrivateTargetIsAuditedWithoutItsQuery() {
        load("http://" + HOST + ":" + port + "/openapi.yaml?token=secret");

        List<AuditEventView> events = storage.listAuditEventsForTenant(100).stream()
                .filter(event -> "PRIVATE_TARGET_ALLOWED".equals(event.action().toString())).toList();
        assertEquals(1, events.size());
        assertEquals("spec-import", events.get(0).resourceId());
        assertEquals("Spec download from private target " + HOST + ":" + port + " (127.0.0.1), allowed by "
                + OutboundUrlPolicy.FALLBACK_RULE, events.get(0).detail());
        assertFalse(events.get(0).detail().contains("secret"));
    }
}
