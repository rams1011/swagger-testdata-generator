package com.tx.api.test.generator.studio.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaseUrlFallbackTest {

    @Test
    void environmentBaseUrlWins() {
        Map<String, String> values = Map.of("baseUrl", "https://int.example.com");
        assertEquals("https://int.example.com",
                TestDataStudioService.withBaseUrlFallback(values, "https://spec.example.com").get("baseUrl"));
    }

    @Test
    void collectionBaseUrlFillsInWhenTheEnvironmentHasNone() {
        assertEquals("https://spec.example.com",
                TestDataStudioService.withBaseUrlFallback(null, " https://spec.example.com ").get("baseUrl"));
        assertEquals("https://spec.example.com",
                TestDataStudioService.withBaseUrlFallback(Map.of("baseUrl", " ", "x", "1"), "https://spec.example.com")
                        .get("baseUrl"));
    }

    @Test
    void placeholderResolvesThroughRunVariables() {
        RunVariables scope = RunVariables.of(
                TestDataStudioService.withBaseUrlFallback(null, "https://spec.example.com"), null, null, null);
        assertEquals("https://spec.example.com/roles",
                CaseVariables.substitute("{{baseUrl}}/roles", scope.newScope()));
    }
}
