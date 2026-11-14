package com.tx.api.test.generator.studio.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StudioEnvironmentNetworkJsonTest {

    private static final String JSON = "{\"proxyUrl\":\"http://proxy:8080\",\"requestTimeoutMs\":5000,"
            + "\"proxyUsername\":\"alice\",\"proxyPassword\":\"{{proxyPassword}}\"}";

    @Test
    void proxyCredentialsSurviveTheApiAndTheEnvironmentFile() throws Exception {
        // the REST API (Jackson) and the environment file (Gson) both read all seven fields
        StudioEnvironmentNetwork fromApi = new ObjectMapper().readValue(JSON, StudioEnvironmentNetwork.class);
        StudioEnvironmentNetwork fromFile = new Gson().fromJson(JSON, StudioEnvironmentNetwork.class);
        for (StudioEnvironmentNetwork network : new StudioEnvironmentNetwork[] {fromApi, fromFile}) {
            assertEquals("alice", network.proxyUsername());
            assertEquals("{{proxyPassword}}", network.proxyPassword());
            assertEquals(5000L, network.requestTimeoutMs());
        }
        StudioEnvironmentNetwork again = new Gson().fromJson(new Gson().toJson(fromApi), StudioEnvironmentNetwork.class);
        assertEquals(fromApi, again);
    }
}
