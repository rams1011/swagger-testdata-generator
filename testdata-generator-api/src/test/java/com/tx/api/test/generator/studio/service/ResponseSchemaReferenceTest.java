package com.tx.api.test.generator.studio.service;

import com.sun.net.httpserver.HttpServer;
import com.tx.api.test.generator.studio.collection.StudioEnvironmentNetwork;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Any request for a schema reaches this local server, so a fetch by the validator is seen. */
class ResponseSchemaReferenceTest {

    private static final String BODY = "{\"a\":1}";

    private final List<String> requested = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            byte[] bytes = (exchange.getRequestURI().getPath().endsWith(".json")
                    ? "{\"type\":\"object\"}" : BODY).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void assertRefused(Map<String, Object> schema, String... hidden) {
        AssertionCheck check = CiResponseChecks.schema(schema, BODY);
        assertFalse(check.passed(), String.valueOf(schema));
        assertTrue(check.actual().startsWith("Could not check the schema"), check.actual());
        for (String text : hidden) {
            assertFalse(check.actual().contains(text), check.actual());
        }
        assertTrue(requested.isEmpty(), "fetched " + requested);
    }

    @Test
    void aRemoteReferenceIsRefusedWithoutAFetch() {
        assertRefused(Map.of("$ref", base + "/schema.json#"), base);
    }

    @Test
    void aNestedRemoteReferenceIsRefusedWithoutAFetch() {
        assertRefused(Map.of("type", "object", "properties", Map.of("a", Map.of("$ref", base + "/schema.json#"))), base);
    }

    @Test
    void anIdCannotTurnARelativeReferenceIntoAFetch() {
        assertRefused(Map.of("id", base + "/root.json", "$ref", "schema.json#"), base);
    }

    @Test
    void aServerFileIsNotRead(@TempDir Path dir) throws IOException {
        // This file is a valid schema that accepts the body, so reading it would make the check pass.
        Path file = Files.writeString(dir.resolve("private.json"), "{\"type\":\"object\"}");
        assertRefused(Map.of("$ref", file.toUri() + "#"), file.toString(), "private.json");
    }

    @Test
    void otherLoadersAreRemovedToo() {
        assertRefused(Map.of("$ref", "resource:/application.yml#"), "application.yml");
        assertRefused(Map.of("$ref", "ftp://127.0.0.1/schema.json#"), "ftp:");
        assertRefused(Map.of("$ref", "jar:" + base + "/a.jar!/schema.json#"), base);
        assertRefused(Map.of("$ref", "https://127.0.0.1/schema.json#"), "https:");
    }

    @Test
    void anUnusedRemoteReferenceIsNotFetched() {
        CiResponseChecks.schema(Map.of("type", "object",
                "definitions", Map.of("x", Map.of("$ref", base + "/schema.json#"))), BODY);
        assertTrue(requested.isEmpty(), "fetched " + requested);
    }

    @Test
    void referencesInsideTheSchemaStillWork() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("a", Map.of("$ref", "#/definitions/count")),
                "definitions", Map.of("count", Map.of("type", "integer")));
        assertTrue(CiResponseChecks.schema(schema, BODY).passed());

        AssertionCheck mismatch = CiResponseChecks.schema(schema, "{\"a\":\"text\"}");
        assertFalse(mismatch.passed());
        assertTrue(mismatch.actual().contains("instance type"), mismatch.actual());
    }

    @Test
    void declaredSchemaVersionsStillWorkWithoutAFetch() {
        for (String version : List.of("http://json-schema.org/draft-04/schema#", "http://json-schema.org/draft-03/schema#",
                "http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft/2020-12/schema")) {
            assertTrue(CiResponseChecks.schema(Map.of("$schema", version, "type", "object"), BODY).passed(), version);
        }
        assertTrue(requested.isEmpty());
    }

    @Test
    void aResponseThatIsNotJsonIsAFailedCheckNotAnError() {
        AssertionCheck check = CiResponseChecks.schema(Map.of("type", "object"), "<html>not json</html>");
        assertFalse(check.passed());
        assertTrue(check.actual().startsWith("Could not check the schema"), check.actual());
    }

    @Test
    void aRunWithARemoteSchemaReferenceFailsTheCheckAndFetchesNothing() {
        StudioProperties properties = new StudioProperties();
        properties.getRun().setAllowPrivateNetworks(true);
        TestRunService service = new TestRunService(null, properties, new OutboundUrlPolicy(properties));
        StudioTestCase testCase = new StudioTestCase();
        testCase.setTestName("schema");
        testCase.setMethod("GET");
        testCase.setBaseUrl(base);
        testCase.setEndpoint("/api");
        testCase.setStatusCode(200);
        testCase.setResponseType("JSON");
        testCase.setMaxResponseTimeMs(5_000);
        testCase.setResponseJsonSchema(new LinkedHashMap<>(Map.of("$ref", base + "/schema.json#")));

        RunTestResult result = service.execute(testCase, null, new LinkedHashMap<>(), (StudioEnvironmentNetwork) null);

        assertFalse(result.passed());
        AssertionCheck check = result.assertions().stream().filter(item -> "jsonSchema".equals(item.name()))
                .findFirst().orElseThrow();
        assertFalse(check.passed());
        assertEquals(List.of("/api"), requested);
    }
}
