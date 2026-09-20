package com.tx.api.test.generator.studio.openapi;

import com.tx.api.test.generator.studio.dto.ManualOperationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualSpecDocumentTest {

    @Test
    void emptyCatalogIsMarkedManual() {
        String content = ManualSpecDocument.emptyCatalog("HRM", "http://localhost:8080");
        assertTrue(ManualSpecDocument.isManualContent(content));
        assertTrue(content.contains("\"paths\""));
        assertTrue(content.contains("http://localhost:8080"));
    }

    @Test
    void addsPathParametersAndRejectsDuplicates() {
        String empty = ManualSpecDocument.emptyCatalog("HRM", "http://localhost:8080");
        String withGet = ManualSpecDocument.addOperation(empty, new ManualOperationRequest(
                "get",
                "users/{id}",
                "Get user",
                "Users",
                null,
                200,
                false,
                null,
                List.of("include"),
                List.of("X-Request-Id"),
                null
        ));
        assertTrue(withGet.contains("\"/users/{id}\""));
        assertTrue(withGet.contains("\"get\""));
        assertTrue(withGet.contains("\"in\": \"path\""));
        assertTrue(withGet.contains("\"include\""));
        assertTrue(withGet.contains("X-Request-Id"));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () ->
                ManualSpecDocument.addOperation(withGet, new ManualOperationRequest(
                        "GET", "/users/{id}", null, null, null, null, null, null, null, null, null)));
        assertTrue(duplicate.getMessage().contains("already"));
    }

    @Test
    void addsJsonBodyAndRemovesOperation() {
        String empty = ManualSpecDocument.emptyCatalog("HRM", "http://localhost:8080");
        String withPost = ManualSpecDocument.addOperation(empty, new ManualOperationRequest(
                "POST",
                "/users",
                "Create user",
                "Users",
                null,
                null,
                true,
                "{\"name\":\"Ada\"}",
                null,
                null,
                List.of("prod")
        ));
        assertTrue(withPost.contains("requestBody"));
        assertTrue(withPost.contains("\"201\""));
        assertTrue(withPost.contains("Ada"));
        assertTrue(withPost.contains("x-testdata-environments"));
        assertTrue(withPost.contains("prod"));

        String cleared = ManualSpecDocument.removeOperation(withPost, "POST", "/users");
        assertFalse(cleared.contains("\"/users\""));
        assertEquals("/users", ManualSpecDocument.normalizePath("users"));
    }

    @Test
    void renameEnvironmentsRewritesOperationScope() {
        String empty = ManualSpecDocument.emptyCatalog("HRM", "http://localhost:8080");
        String withPost = ManualSpecDocument.addOperation(empty, new ManualOperationRequest(
                "POST", "/users", "Create user", "Users", null, null, true, "{\"name\":\"Ada\"}",
                null, null, List.of("local")));
        String renamed = ManualSpecDocument.renameEnvironments(withPost, "local", "dev");
        assertTrue(renamed.contains("\"dev\""));
        assertFalse(renamed.contains("\"local\""));
        assertEquals(withPost, ManualSpecDocument.renameEnvironments(withPost, "local", "local"));
    }
}
