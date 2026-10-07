package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentOverlayTest {

    @Test
    void mergeReplacesPresentFieldsAndLeavesTheRest() {
        StudioTestCase base = new StudioTestCase();
        base.setId("case-create-role");
        base.setRawPath("/api/roles/{id}");
        base.setEndpoint("/api/roles/local");
        base.setPathParams(Map.of("id", "local"));
        base.setRequestBody(Map.of("name", "Local Admin"));
        base.setExpectedResponse("{\"id\":\"local\"}");
        base.setStatusCode(200);
        base.setJsonPathAssertions("$.name=Local Admin");
        base.setQueryParams(Map.of("region", "us-east"));
        base.setTestName("create — 200");

        StudioEnvironmentCaseOverlay overlay = new StudioEnvironmentCaseOverlay(
                Map.of("name", "Int Admin"),
                "{\"id\":\"94821\"}",
                Map.of("id", "94821"),
                null,
                Map.of("X-Tenant", "acme"),
                null,
                201,
                null
        );

        StudioTestCase merged = EnvironmentOverlay.merge(base, overlay);
        assertEquals("Int Admin", ((Map<?, ?>) merged.getRequestBody()).get("name"));
        assertEquals("{\"id\":\"94821\"}", merged.getExpectedResponse());
        assertEquals("/api/roles/94821", merged.getEndpoint());
        assertEquals("94821", merged.getPathParams().get("id"));
        assertEquals("us-east", merged.getQueryParams().get("region"));
        assertEquals("acme", merged.getHeaders().get("X-Tenant"));
        assertEquals(201, merged.getStatusCode());
        assertEquals("$.name=Local Admin", merged.getJsonPathAssertions());
        assertEquals("create — 200", merged.getTestName());
        assertEquals("local", base.getPathParams().get("id"));
    }

    @Test
    void missingOverlayFallsBackToTheCase() {
        StudioEnvironment environment = EnvironmentMapper.fromInputs("int", "https://int.example/api", "NONE");
        assertFalse(EnvironmentOverlay.hasOverlay(environment, "case-create-role"));
        StudioEnvironment withOverlay = EnvironmentOverlay.withCase(environment, "case-create-role",
                new StudioEnvironmentCaseOverlay(Map.of("code", "INT"), null, null, null, null, null, null, null));
        assertTrue(EnvironmentOverlay.hasOverlay(withOverlay, "case-create-role"));
        assertEquals("INT", ((Map<?, ?>) withOverlay.caseOverlays().get("case-create-role").body()).get("code"));
        assertFalse(EnvironmentOverlay.hasOverlay(EnvironmentOverlay.withoutCase(withOverlay, "case-create-role"),
                "case-create-role"));
    }

    @Test
    void mergePreservesRevisionFromBase() {
        StudioTestCase base = new StudioTestCase();
        base.setId("case-1");
        Instant updatedAt = Instant.parse("2026-01-01T12:00:00.123Z");
        base.setUpdatedAt(updatedAt);
        StudioEnvironmentCaseOverlay overlay = new StudioEnvironmentCaseOverlay(
                Map.of("name", "Int"), null, null, null, null, null, null, null);
        StudioTestCase merged = EnvironmentOverlay.merge(base, overlay);
        assertEquals(updatedAt, merged.getUpdatedAt());
    }

    @Test
    void copyDoesNotShareMutableMaps() {
        StudioTestCase source = new StudioTestCase();
        source.setId("case-1");
        source.setPathParams(new LinkedHashMap<>(Map.of("id", "local")));
        source.setEnvironments(new ArrayList<>(List.of("int")));

        StudioTestCase copy = EnvironmentOverlay.copy(source);
        copy.setId("case-2");
        copy.getPathParams().put("id", "prod");
        copy.getEnvironments().add("prod");

        assertEquals("case-1", source.getId());
        assertEquals("local", source.getPathParams().get("id"));
        assertEquals(List.of("int"), source.getEnvironments());
    }
}
