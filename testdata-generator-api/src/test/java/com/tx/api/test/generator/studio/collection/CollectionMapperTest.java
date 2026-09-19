package com.tx.api.test.generator.studio.collection;

import com.google.gson.JsonElement;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionMapperTest {

    @Test
    void goldenFixturePutsCreateBeforeGet() throws Exception {
        StudioCollection collection = CollectionDocuments.parseCollection(read("employee-service.Default.collection.json"));

        assertEquals("testdata.studio.collection", collection.format());
        assertEquals(1, collection.version());
        assertEquals("employee-service", collection.spec().name());
        assertEquals("Default", collection.plan().name());
        assertEquals(2, collection.cases().size());
        assertEquals("case-create-role", collection.cases().get(0).id());
        assertEquals("roleId=$.roleId", collection.cases().get(0).extracts());
        assertEquals("case-get-role", collection.cases().get(1).id());
        assertEquals("{{roleId}}", collection.cases().get(1).pathParams().get("id"));
        assertEquals("/api/roles/{{roleId}}", collection.cases().get(1).endpoint());
        assertEquals("{{baseUrl}}", collection.cases().get(0).baseUrl());
        assertEquals("{{baseUrl}}", collection.cases().get(1).baseUrl());
    }

    @Test
    void goldenFixtureRoundTripsThroughTheMapper() throws Exception {
        String golden = read("employee-service.Default.collection.json");
        StudioCollection parsed = CollectionDocuments.parseCollection(golden);
        CollectionSnapshot snapshot = CollectionMapper.toSnapshot(parsed);
        StudioCollection exported = CollectionMapper.fromPlan(snapshot.spec(), snapshot.plan(), snapshot.cases());

        assertEquals(tree(golden), tree(CollectionDocuments.toJson(exported)));
        assertEquals("case-create-role", snapshot.cases().get(0).getId());
        assertEquals("roleId=$.roleId", snapshot.cases().get(0).getExtracts());
        assertEquals("{{roleId}}", snapshot.cases().get(1).getPathParams().get("id"));
        assertEquals("ADMIN", ((Map<?, ?>) snapshot.cases().get(0).getRequestBody()).get("code"));
        assertEquals("http://localhost:8080/employee-service", snapshot.cases().get(0).getBaseUrl());
    }

    @Test
    void ordersExtractProducersBeforeConsumers() {
        StudioTestCase get = testCase("case-get-role", "get", "GET", "/api/roles/{id}", "/api/roles/{{roleId}}",
                Map.of("id", "{{roleId}}"), null, null);
        StudioTestCase list = testCase("case-list-roles", "list", "GET", "/api/roles", "/api/roles",
                Map.of(), null, null);
        StudioTestCase create = testCase("case-create-role", "create", "POST", "/api/roles", "/api/roles",
                Map.of(), Map.of("name", "Admin"), "roleId=$.roleId");

        StudioCollection collection = CollectionMapper.fromPlan(spec(), plan(), List.of(get, list, create));

        assertEquals(List.of("case-list-roles", "case-create-role", "case-get-role"),
                collection.cases().stream().map(StudioCollectionCase::id).toList());
        assertEquals("{{baseUrl}}", collection.cases().get(0).baseUrl());
    }

    @Test
    void keepsACaseBaseUrlThatDiffersFromTheSpec() {
        StudioTestCase custom = testCase("case-list-roles", "list", "GET", "/api/roles", "/api/roles",
                Map.of(), null, null);
        custom.setBaseUrl("https://override.example/employee-service");

        StudioCollection collection = CollectionMapper.fromPlan(spec(), plan(), List.of(custom));

        assertEquals("https://override.example/employee-service", collection.cases().get(0).baseUrl());
        assertEquals("https://override.example/employee-service",
                CollectionMapper.toSnapshot(collection).cases().get(0).getBaseUrl());
    }

    @Test
    void environmentGoldenFixtureParses() throws Exception {
        StudioEnvironment environment = CollectionDocuments.parseEnvironment(
                read("employee-service.local.environment.json"));
        assertEquals("local", environment.name());
        assertEquals("http://localhost:8080/employee-service", environment.values().get("baseUrl"));
        assertEquals("BEARER", environment.auth().type());
        assertEquals("${AUTOMATION_BEARER_TOKEN}", environment.auth().token());
        assertEquals(tree(read("employee-service.local.environment.json")),
                tree(CollectionDocuments.toJson(environment)));
        StudioEnvironment integration = CollectionDocuments.parseEnvironment(
                read("employee-service.int.environment.json"));
        assertEquals("int", integration.name());
        assertEquals("https://int.example.com/employee-service", integration.values().get("baseUrl"));
    }

    @Test
    void rejectsUnknownCollectionFormat() {
        CollectionFormatException error = assertThrows(CollectionFormatException.class,
                () -> CollectionDocuments.parseCollection("""
                        {"format":"postman.collection","version":1,"spec":{"id":"s","name":"s"},"plan":{"id":"p","name":"p"},"cases":[]}
                        """));
        assertTrue(error.getMessage().contains("format"));
    }

    private static StoredSpec spec() {
        StoredSpec spec = new StoredSpec();
        spec.setSpecId("spec-employee-service");
        spec.setName("employee-service");
        spec.setLabel("General");
        spec.setTitle("Employee Service");
        spec.setVersion("1.0");
        spec.setBaseUrl("http://localhost:8080/employee-service");
        return spec;
    }

    private static StudioTestPlan plan() {
        StudioTestPlan plan = new StudioTestPlan();
        plan.setId("plan-default");
        plan.setSpecId("spec-employee-service");
        plan.setName("Default");
        return plan;
    }

    private static StudioTestCase testCase(String id, String operationKey, String method, String path, String endpoint,
                                           Map<String, String> pathParams, Map<String, String> body, String extracts) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setId(id);
        testCase.setSpecId("spec-employee-service");
        testCase.setPlanId("plan-default");
        testCase.setOperationKey(operationKey);
        testCase.setTestName(operationKey + " — 200");
        testCase.setMethod(method);
        testCase.setRawPath(path);
        testCase.setEndpoint(endpoint);
        testCase.setBaseUrl("http://localhost:8080/employee-service");
        testCase.setContentType("application/json");
        testCase.setPathParams(new LinkedHashMap<>(pathParams));
        testCase.setRequestBody(body);
        testCase.setStatusCode(200);
        testCase.setResponseType("JSON");
        testCase.setEnabled("Y");
        testCase.setTestGroup("role-controller");
        testCase.setExtracts(extracts);
        testCase.setMaxResponseTimeMs(3000);
        return testCase;
    }

    private static JsonElement tree(String json) {
        return CollectionDocuments.parseTree(json);
    }

    private static String read(String name) throws IOException {
        try (InputStream stream = CollectionMapperTest.class.getResourceAsStream("/collection/" + name)) {
            if (stream == null) {
                throw new IOException("Missing fixture: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
