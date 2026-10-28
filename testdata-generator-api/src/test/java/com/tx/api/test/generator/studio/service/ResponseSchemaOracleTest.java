package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.catalog.ResponseSchema;
import com.tx.api.test.generator.studio.model.LoadedSpec;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseSchemaOracleTest {

    @Test
    void liveSchemaReplacesAnEmptyOrStaleCopy() {
        Map<String, Object> live = Map.of("type", "object", "required", List.of("id"));
        LoadedSpec spec = spec("getPet", live);
        StudioTestCase stale = testCase("getPet", Map.of("type", "object"));

        ResponseSchemaOracle.apply(stale, spec);

        assertEquals(List.of("id"), stale.getResponseJsonSchema().get("required"));
    }

    @Test
    void missingOperationKeepsTheStoredSchema() {
        LoadedSpec spec = spec("getPet", Map.of("type", "object"));
        Map<String, Object> stored = Map.of("type", "string");
        StudioTestCase orphan = testCase("removed", stored);

        ResponseSchemaOracle.apply(orphan, spec);

        assertEquals("string", orphan.getResponseJsonSchema().get("type"));
        assertTrue(ResponseSchemaOracle.unchanged(stored, ResponseSchemaOracle.lookup(spec, "removed")));
    }

    @Test
    void emptyOperationSchemaClearsAStaleCopy() {
        LoadedSpec spec = spec("getPet", Map.of());
        StudioTestCase stale = testCase("getPet", Map.of("type", "object"));

        ResponseSchemaOracle.apply(stale, spec);

        assertNull(stale.getResponseJsonSchema());
        assertTrue(ResponseSchemaOracle.same(null, Map.of()));
    }

    private static StudioTestCase testCase(String operationKey, Map<String, Object> schema) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setSpecId("spec");
        testCase.setOperationKey(operationKey);
        testCase.setResponseJsonSchema(schema);
        return testCase;
    }

    private static LoadedSpec spec(String operationKey, Map<String, Object> schema) {
        OperationPage operation = new OperationPage(
                operationKey, "GET", "/pets", "/pets", "Get pet", null, "pets",
                List.of(), null,
                new ResponseSchema(200, "application/json", "JSON", schema, null, "NA"),
                List.of(200), null, List.of(), null, List.of());
        return new LoadedSpec("spec", "pets", null, List.of(operation), "Pets", "1", null, "http://localhost");
    }
}
