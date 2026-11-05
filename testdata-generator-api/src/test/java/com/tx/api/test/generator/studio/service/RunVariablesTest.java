package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.collection.StudioVariable;
import com.tx.api.test.generator.studio.dto.AssertionCheck;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunVariablesTest {

    @Test
    void environmentVariablesOverrideGlobalsAndSecretsComeFromTheCaller() {
        RunVariables scope = RunVariables.of(
                Map.of("baseUrl", "https://int.example.com"),
                List.of(new StudioVariable("tenant", "global", false),
                        new StudioVariable("region", "eu", false),
                        new StudioVariable("apiKey", null, true)),
                List.of(new StudioVariable("tenant", "acme", false),
                        new StudioVariable("password", null, true)),
                Map.of("apiKey", "k-123", "password", "", "unrelated", "x"));

        Map<String, String> values = scope.newScope();
        assertEquals("https://int.example.com", values.get("baseUrl"));
        assertEquals("acme", values.get("tenant"));
        assertEquals("eu", values.get("region"));
        assertEquals("k-123", values.get("apiKey"));
        assertFalse(values.containsKey("password"));
        assertFalse(values.containsKey("unrelated"));

        values.put("extracted", "1");
        assertFalse(scope.newScope().containsKey("extracted"));
    }

    @Test
    void maskHidesSecretValuesInEveryResultField() {
        RunVariables scope = RunVariables.of(null, List.of(new StudioVariable("apiKey", null, true)), null,
                Map.of("apiKey", "s3cr3t value"));
        RunTestResult result = new RunTestResult(false, false, null, "c1", "Get", "GET",
                "https://x/items?key=s3cr3t+value", 200, 401, false, 5, 0, false,
                "{\"echo\":\"s3cr3t value\"}", Map.of("X-Echo", "s3cr3t value"),
                Map.of("X-Api-Key", "s3cr3t value"),
                List.of(new AssertionCheck("header", "X-Api-Key", "s3cr3t value", "other", false)),
                "Rejected s3cr3t value", null, Map.of("token", "s3cr3t value"));

        RunTestResult masked = scope.mask(result);
        assertEquals("https://x/items?key=" + RunVariables.MASK, masked.url());
        assertEquals(RunVariables.MASK, masked.requestHeaders().get("X-Api-Key"));
        assertEquals(RunVariables.MASK, masked.responseHeaders().get("X-Echo"));
        assertFalse(masked.responseBody().contains("s3cr3t"));
        assertEquals(RunVariables.MASK, masked.assertions().get(0).expected());
        assertEquals("Rejected " + RunVariables.MASK, masked.error());
        assertEquals(RunVariables.MASK, masked.extracted().get("token"));
    }

    @Test
    void dynamicVariablesResolveFreshOnEachUse() {
        String text = CaseVariables.substitute("{{$guid}}|{{$guid}}|{{$timestamp}}|{{$randomInt}}", Map.of());
        String[] parts = text.split("\\|");
        assertTrue(Pattern.matches("[0-9a-f-]{36}", parts[0]));
        assertNotEquals(parts[0], parts[1]);
        assertTrue(Pattern.matches("\\d{10}", parts[2]));
        int random = Integer.parseInt(parts[3]);
        assertTrue(random >= 0 && random <= 1000);
        assertTrue(DynamicVariables.value("$randomEmail").endsWith("@example.com"));

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> CaseVariables.substitute("{{$nope}}", Map.of()));
        assertTrue(unknown.getMessage().contains("Unknown dynamic variable"));
        IllegalArgumentException unresolved = assertThrows(IllegalArgumentException.class,
                () -> CaseVariables.substitute("{{missing}}", Map.of()));
        assertTrue(unresolved.getMessage().contains("environment or global variables"));
    }

    @Test
    void definedVariablesAreNotTreatedAsChainPrerequisites() {
        StudioTestCase producer = new StudioTestCase();
        producer.setId("p");
        producer.setExtracts("tenant=$.tenant");
        StudioTestCase consumer = new StudioTestCase();
        consumer.setId("c");
        consumer.setHeaders(Map.of("X-Tenant", "{{tenant}}", "X-Id", "{{$guid}}"));

        assertEquals(1, CaseVariables.prerequisites(List.of(producer, consumer), consumer).size());
        assertTrue(CaseVariables.prerequisites(List.of(producer, consumer), consumer, Set.of("tenant")).isEmpty());
        assertEquals(Set.of("tenant"), CaseVariables.placeholderNames(consumer));
    }

    @Test
    void qualifiedNamesReadOneLayerEvenWhenAHigherOneOverrides() {
        RunVariables scope = RunVariables.of(
                Map.of("baseUrl", "https://int.example.com"),
                List.of(new StudioVariable("tenant", "global", false),
                        new StudioVariable("apiKey", null, true),
                        new StudioVariable("only", "g", false)),
                List.of(new StudioVariable("tenant", "acme", false),
                        new StudioVariable("password", null, true)),
                Map.of("apiKey", "k-123"));

        Map<String, String> values = scope.newScope();
        assertEquals("acme", values.get("tenant"));
        assertEquals("global", values.get("global:tenant"));
        assertEquals("acme", values.get("env:tenant"));
        assertEquals("https://int.example.com", values.get("env:baseUrl"));
        assertEquals("k-123", values.get("global:apiKey"));
        assertEquals("g", values.get("global:only"));
        assertFalse(values.containsKey("env:only"));
        assertFalse(values.containsKey("env:password"));

        StudioTestCase testCase = new StudioTestCase();
        testCase.setHeaders(Map.of("X-Tenant", "{{global:tenant}}/{{tenant}}/{{env:tenant}}"));
        assertEquals("global/acme/acme", CaseVariables.bind(testCase, values).getHeaders().get("X-Tenant"));

        StudioTestCase missing = new StudioTestCase();
        missing.setHeaders(Map.of("X", "{{env:only}}"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CaseVariables.bind(missing, values));
        assertTrue(error.getMessage().contains("selected environment"));
    }

    @Test
    void extractQualifierChainsToTheProducerAndSkipsOverridingValues() {
        StudioTestCase create = new StudioTestCase();
        create.setId("create");
        create.setExtracts("orderId=$.id");
        StudioTestCase get = new StudioTestCase();
        get.setId("get");
        get.setEndpoint("/orders/{{extract:orderId}}");

        assertEquals(List.of("create", "get"),
                CaseVariables.orderForChain(List.of(get, create)).stream().map(StudioTestCase::getId).toList());
        assertEquals(List.of(create), CaseVariables.prerequisites(List.of(create, get), get, Set.of("orderId")));

        Map<String, String> scope = new java.util.LinkedHashMap<>(Map.of("orderId", "from-env"));
        CaseVariables.putExtracted(scope, Map.of("orderId", "42"));
        assertEquals("/orders/42", CaseVariables.bind(get, scope).getEndpoint());
        assertTrue(CaseVariables.producedNames("orderId=$.id").containsAll(Set.of("orderId", "extract:orderId")));
    }

    @Test
    void numericExtractKeepsItsJsonTypeInQuotedWholeValuePlaceholders() {
        Map<String, String> extracted = new java.util.LinkedHashMap<>();
        Map<String, String> json = new java.util.LinkedHashMap<>();
        List<AssertionCheck> checks = CaseVariables.extractInto(extracted, json,
                "roleId=$.roleId|code=$.code|active=$.active", "{\"roleId\":48,\"code\":\"007\",\"active\":true}",
                Map.of());
        assertTrue(checks.stream().allMatch(AssertionCheck::passed));
        assertEquals("48", extracted.get("roleId"));
        assertFalse(json.containsKey("code"));

        Map<String, String> scope = new java.util.LinkedHashMap<>();
        CaseVariables.putExtracted(scope, extracted, json);
        StudioTestCase get = new StudioTestCase();
        get.setEndpoint("/roles/{{roleId}}");
        get.setExpectedResponse("{\"roleId\":\"{{roleId}}\",\"code\":\"{{code}}\",\"label\":\"role-{{roleId}}\","
                + "\"active\":\"{{extract:active}}\"}");
        get.setRequestBody(new java.util.LinkedHashMap<>(Map.of("roleId", "{{roleId}}", "code", "{{code}}")));

        StudioTestCase bound = CaseVariables.bind(get, scope);
        assertEquals("/roles/48", bound.getEndpoint());
        assertEquals(com.google.gson.JsonParser.parseString(
                        "{\"roleId\":48,\"code\":\"007\",\"label\":\"role-48\",\"active\":true}"),
                com.google.gson.JsonParser.parseString(bound.getExpectedResponse()));
        Map<?, ?> body = (Map<?, ?>) bound.getRequestBody();
        assertEquals(0, new java.math.BigDecimal("48").compareTo((java.math.BigDecimal) body.get("roleId")));
        assertEquals("007", body.get("code"));

        CaseVariables.putExtracted(scope, Map.of("roleId", "abc"), Map.of());
        assertEquals("{\"roleId\":\"abc\"}",
                CaseVariables.substitute("{\"roleId\":\"{{roleId}}\"}", scope));
        get.setExpectedResponse("{\"roleId\":\"{{roleId}}\"}");
        assertEquals("{\"roleId\":\"abc\"}", CaseVariables.bind(get, scope).getExpectedResponse());
    }

    @Test
    void quotedTypedPlaceholdersInAssertionsBecomeJsonValues() {
        Map<String, String> scope = new java.util.LinkedHashMap<>();
        CaseVariables.putExtracted(scope, Map.of("roleId", "48", "code", "TestCode"),
                Map.of("roleId", "48"));
        StudioTestCase list = new StudioTestCase();
        list.setEndpoint("/roles");
        list.setJsonPathAssertions("$.items=op:containsObject:{\"roleId\":\"{{roleId}}\",\"code\":\"{{code}}\"}"
                + "|$.items[?(@.roleId=='{{roleId}}')].code=TestCode");
        assertEquals("$.items=op:containsObject:{\"roleId\":48,\"code\":\"TestCode\"}"
                        + "|$.items[?(@.roleId==48)].code=TestCode",
                CaseVariables.bind(list, scope).getJsonPathAssertions());
    }
}
