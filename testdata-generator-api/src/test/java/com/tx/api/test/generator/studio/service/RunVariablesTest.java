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
    void exportFillsKnownValuesAndKeepsOtherPlaceholders() {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setBaseUrl("{{baseUrl}}");
        testCase.setEndpoint("/orders/{{orderId}}");
        testCase.setHeaders(Map.of("X-Tenant", "{{tenant}}", "X-Key", "{{apiKey}}", "X-Id", "{{$guid}}"));
        testCase.setEnvironments(List.of("int"));

        StudioTestCase bound = CaseVariables.bindKnown(testCase,
                Map.of("baseUrl", "https://int.example.com", "tenant", "acme"));
        assertEquals("https://int.example.com", bound.getBaseUrl());
        assertEquals("/orders/{{orderId}}", bound.getEndpoint());
        assertEquals("acme", bound.getHeaders().get("X-Tenant"));
        assertEquals("{{apiKey}}", bound.getHeaders().get("X-Key"));
        assertEquals("{{$guid}}", bound.getHeaders().get("X-Id"));
        assertEquals(List.of("int"), bound.getEnvironments());
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
}
