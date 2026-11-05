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

    @Test
    void requestsCarryNetworkSettingsCaseAuthAndRunOptions() throws Exception {
        SaveEnvironmentRequest save = mapper.readValue("{\"name\":\"int\",\"baseUrl\":\"https://x\","
                + "\"network\":{\"followRedirects\":true,\"verifySsl\":false,\"proxyUrl\":\"http://proxy:3128\","
                + "\"requestTimeoutMs\":120000}}", SaveEnvironmentRequest.class);
        assertTrue(save.network().followsRedirects());
        assertFalse(save.network().verifiesSsl());
        assertEquals(120_000L, save.network().requestTimeoutMs());
        assertNull(mapper.readValue("{\"name\":\"int\"}", SaveEnvironmentRequest.class).network());

        SaveTestDataRequest testCase = mapper.readValue("{\"specId\":\"s\",\"auth\":{\"type\":\"OAUTH2\","
                + "\"clientId\":\"{{clientId}}\",\"clientSecret\":\"{{clientSecret}}\",\"tokenUrl\":\"https://idp/token\","
                + "\"scope\":\"roles.read\"}}", SaveTestDataRequest.class);
        assertEquals("roles.read", testCase.auth().scope());
        assertEquals("{{clientSecret}}", testCase.auth().clientSecret());

        RunPlanRequest plan = mapper.readValue("{\"specId\":\"s\",\"planId\":\"p\",\"delayMs\":250,\"retries\":2,"
                + "\"auth\":{\"type\":\"OAUTH2\",\"scope\":\"a b\"}}", RunPlanRequest.class);
        assertEquals(250L, plan.delayMillis());
        assertEquals(2, plan.retryCount());
        assertEquals("a b", plan.auth().scope());
    }
}
