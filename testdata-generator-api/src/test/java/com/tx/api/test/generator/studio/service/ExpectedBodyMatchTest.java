package com.tx.api.test.generator.studio.service;

import com.sun.net.httpserver.HttpServer;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpectedBodyMatchTest {

    private static final String ROLES = "{\"total\":2,\"items\":[{\"roleId\":48,\"code\":\"TestCode\",\"name\":\"Test\"},"
            + "{\"roleId\":12,\"code\":\"Admin\",\"name\":\"Admin\"}]}";

    private HttpServer server;
    private TestRunService service;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/roles", exchange -> {
            byte[] body = ROLES.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        StudioProperties properties = new StudioProperties();
        properties.getRun().setAllowPrivateNetworks(true);
        service = new TestRunService(null, properties, new OutboundUrlPolicy(properties));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void strictReportsWhereTheBodiesDiffer() {
        AssertionCheck check = expectedBody(run("{\"total\":2,\"items\":[{\"roleId\":12,\"code\":\"Admin\",\"name\":\"Admin\"},"
                + "{\"roleId\":48,\"code\":\"TestCode\",\"name\":\"Test\"}]}", null));
        assertFalse(check.passed());
        assertEquals("$.items[0].roleId: expected 12 but was 48", check.path());
    }

    @Test
    void ignoreOrderPassesForAReorderedList() {
        RunTestResult result = run("{\"total\":2,\"items\":[{\"roleId\":12,\"code\":\"Admin\",\"name\":\"Admin\"},"
                + "{\"roleId\":48,\"code\":\"TestCode\",\"name\":\"Test\"}]}", "ignoreOrder");
        assertTrue(result.passed(), String.valueOf(result.assertions()));
        assertEquals("ignore list order", expectedBody(result).path());
    }

    @Test
    void lenientPassesForASubsetWithAnExtractedNumber() {
        Map<String, String> scope = new LinkedHashMap<>();
        CaseVariables.putExtracted(scope, Map.of("roleId", "48"), Map.of("roleId", "48"));
        StudioTestCase testCase = testCase("{\"items\":[{\"roleId\":\"{{roleId}}\",\"name\":\"Test\"}]}", "lenient");
        testCase.setJsonPathAssertions("$.items=op:containsObject:{\"roleId\":\"{{roleId}}\",\"code\":\"TestCode\"}"
                + "|$.items[?(@.roleId=={{roleId}})].name=Test|$.items[?(@.code=='TestCode')]=op:length:1");
        RunTestResult result = service.execute(testCase, null, scope);
        assertTrue(result.passed(), String.valueOf(result.assertions()));

        AssertionCheck failing = expectedBody(run("{\"items\":[{\"code\":\"Nope\"}]}", "lenient"));
        assertFalse(failing.passed());
        assertEquals("lenient · $.items: no item matches expected[0] {\"code\":\"Nope\"}", failing.path());
    }

    @Test
    void strictMatchHasNoDetail() {
        AssertionCheck check = expectedBody(run(ROLES, "strict"));
        assertTrue(check.passed());
        assertNull(check.path());
    }

    private RunTestResult run(String expected, String mode) {
        return service.execute(testCase(expected, mode), null);
    }

    private StudioTestCase testCase(String expected, String mode) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setTestName("list roles");
        testCase.setMethod("GET");
        testCase.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        testCase.setEndpoint("/roles");
        testCase.setStatusCode(200);
        testCase.setResponseType("JSON");
        testCase.setExpectedResponse(expected);
        testCase.setResponseMatch(mode);
        testCase.setMaxResponseTimeMs(5000);
        return testCase;
    }

    private static AssertionCheck expectedBody(RunTestResult result) {
        return result.assertions().stream().filter(item -> "expectedResponse".equals(item.name())).findFirst()
                .orElseThrow();
    }
}
