package com.tx.api.test.generator.studio.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tx.api.test.generator.studio.collection.StudioVariable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requestsCarryVariablesAndSecrets() throws Exception {
        RunTestRequest run = mapper.readValue(
                "{\"caseId\":\"c1\",\"environmentId\":\"e1\",\"secrets\":{\"apiKey\":\"k\"}}", RunTestRequest.class);
        assertEquals(Map.of("apiKey", "k"), run.secrets());
        assertFalse(run.toString().contains("\"k\""));

        RunPlanRequest plan = mapper.readValue(
                "{\"specId\":\"s\",\"planId\":\"p\",\"secrets\":{\"apiKey\":\"k\"}}", RunPlanRequest.class);
        assertEquals("k", plan.secrets().get("apiKey"));
        assertFalse(plan.continuesOnFailure());
        assertTrue(mapper.readValue("{\"specId\":\"s\",\"planId\":\"p\",\"continueOnFailure\":true}",
                RunPlanRequest.class).continuesOnFailure());

        SaveEnvironmentRequest save = mapper.readValue(
                "{\"name\":\"int\",\"baseUrl\":\"http://x\",\"variables\":[{\"key\":\"a\",\"value\":\"1\",\"secret\":false}]}",
                SaveEnvironmentRequest.class);
        assertEquals(List.of(new StudioVariable("a", "1", false)), save.variables());
        assertNull(mapper.readValue("{\"name\":\"int\"}", SaveEnvironmentRequest.class).variables());

        GlobalVariables globals = mapper.readValue(
                "{\"variables\":[{\"key\":\"token\",\"value\":null,\"secret\":true}]}", GlobalVariables.class);
        assertEquals(new StudioVariable("token", null, true), globals.variableList().get(0));
    }
}
