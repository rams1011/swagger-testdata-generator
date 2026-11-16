package com.tx.api.test.generator.studio.collection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tx.api.test.generator.studio.audit.StudioAuditService;
import com.tx.api.test.generator.studio.dto.EnvironmentSummary;
import com.tx.api.test.generator.studio.dto.ExportRequest;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.ManualOperationRequest;
import com.tx.api.test.generator.studio.dto.SaveEnvironmentRequest;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;
import com.tx.api.test.generator.studio.dto.SaveTestPlanRequest;
import com.tx.api.test.generator.studio.dto.TestCaseView;
import com.tx.api.test.generator.studio.service.SpecCatalogService;
import com.tx.api.test.generator.studio.service.TestDataStudioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loading a definition, adding an API and importing files apply the same rules as the editor's save. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=local",
                "TESTDATA_EDITION=local",
                "TESTDATA_H2_FILE=target/entry-point-rules-test/testdata",
                "TESTDATA_H2_JDBC_URL=jdbc:h2:mem:entry_point_rules;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
        })
class EntryPointRulesTest {

    private static final String DEFINITION = """
            {"openapi":"3.0.3","info":{"title":"T","version":"1"},"paths":{"/ok":{"post":{"operationId":"ok",
            "requestBody":{"content":{"application/json":{"schema":{"type":"object"}}}},
            "responses":{"200":{"description":"OK"}}}}}}
            """;

    @Autowired
    private TestDataStudioService studio;
    @Autowired
    private SpecCatalogService catalog;
    @Autowired
    private StudioAuditService audit;

    private String specId;
    private String planId;

    @BeforeEach
    void newCollection() {
        specId = catalog.load(load("Entry " + UUID.randomUUID(), null, "http://127.0.0.1:1")).specId();
        planId = studio.listPlans(specId).get(0).id();
    }

    @Test
    void loadingADefinitionChecksBaseUrlNameAndLabel() {
        assertMessage("API base URL must start with", () -> catalog.load(load("Bad base", null, "not a url")));
        assertMessage("API base URL port must be from 1 to 65535",
                () -> catalog.load(load("Bad port", null, "https://httpbin.org:99999")));
        assertMessage("Collection name can be at most 255", () -> catalog.load(load("n".repeat(300), null, null)));
        assertMessage("Label can be at most 255", () -> catalog.load(load("Long label", "l".repeat(300), null)));
        String name = catalog.getSummary(specId).name();
        assertMessage("A collection named", () -> catalog.load(load(name.toUpperCase(), null, null)));
        assertMessage("API base URL must start with", () -> catalog.load(
                new LoadSpecRequest(null, null, null, "ftp://x", "Catalog " + UUID.randomUUID(), null, true, null, null)));
    }

    @Test
    void creatingTheCollectionIsAudited() {
        assertTrue(audit.listForSpec(specId, 10).stream().anyMatch(event -> "CREATED".equals(event.action())));
    }

    @Test
    void planNamesAreLimited() {
        assertMessage("Plan name can be at most 255",
                () -> studio.createPlan(specId, new SaveTestPlanRequest("p".repeat(300), null)));
    }

    @Test
    void addApiRefusesBrokenPathsAndHeaderNames() {
        String catalogId = catalog.load(new LoadSpecRequest(null, null, null, "http://127.0.0.1:1",
                "Catalog " + UUID.randomUUID(), null, true, null, null)).specId();
        for (String bad : List.of("/a b", "/x/{id", "/x/{}", "/x?y=1", "/x/{a{b}}")) {
            assertThrows(IllegalArgumentException.class, () -> catalog.addOperation(catalogId, api(bad, null)), bad);
        }
        assertMessage("Header 'Host' can't be set", () -> catalog.addOperation(catalogId, api("/h", List.of("Host"))));
        assertMessage("Header 'Bad:Name' isn't a valid header name",
                () -> catalog.addOperation(catalogId, api("/h", List.of("Bad:Name"))));
        catalog.addOperation(catalogId, api("users/{id}/posts/{post_id}", List.of("X-Trace")));
    }

    @Test
    void anEnvironmentFileGetsTheEditorsChecks() {
        assertMessage("Base URL must start with", () -> studio.importEnvironment(environmentFile("Imported", "not a url"),
                specId, true));
        assertMessage("Environment name can be at most 255",
                () -> studio.importEnvironment(environmentFile("e".repeat(300), "https://x.example.com"), specId, true));
        studio.importEnvironment(environmentFile("Imported", "https://x.example.com"), specId, false);
        assertTrue(audit.listForSpec(specId, 20).stream().anyMatch(event -> "IMPORTED".equals(event.action())));
    }

    @Test
    void aCollectionFileGetsTheCaseChecksAndKeepsCasesInTheirEnvironments() {
        EnvironmentSummary other = studio.createEnvironment(specId,
                new SaveEnvironmentRequest("Other", "http://127.0.0.1:1", "NONE"));
        EnvironmentSummary first = studio.createEnvironment(specId,
                new SaveEnvironmentRequest("Main", "http://127.0.0.1:1", "NONE"));
        String defaultId = first.id();
        TestCaseView saved = studio.create(save("In default", List.of(first.name())));
        byte[] exported = studio.exportCollection(new ExportRequest(specId, planId, defaultId)).content();

        for (String[] change : new String[][] {
                {"testName", "\"" + "n".repeat(300) + "\"", "Test name can be at most 255"},
                {"maxResponseTimeMs", "-5", "Max response time must be from 1"},
                {"ignoreFields", "\"$.[\"", "Ignore field $.[ is not a valid JSONPath"},
                {"headers", "{\"Host\":\"evil\"}", "Header 'Host' can't be set"}}) {
            JsonObject collection = JsonParser.parseString(new String(exported, StandardCharsets.UTF_8)).getAsJsonObject();
            collection.getAsJsonArray("cases").get(0).getAsJsonObject().add(change[0], JsonParser.parseString(change[1]));
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> studio.importCollection(
                    collection.toString().getBytes(StandardCharsets.UTF_8), "c.json", specId, other.id()), change[0]);
            assertTrue(refused.getMessage().contains(change[2]), refused.getMessage());
        }

        studio.importCollection(exported, "c.json", specId, other.id());
        assertEquals(List.of(first.name(), "Other"), studio.get(saved.id(), null).environments(),
                "importing with another environment selected adds it instead of moving the case");
    }

    private LoadSpecRequest load(String name, String label, String baseUrl) {
        return new LoadSpecRequest(null, DEFINITION, null, baseUrl, name, label, null, null, null);
    }

    private static ManualOperationRequest api(String path, List<String> headers) {
        return new ManualOperationRequest("GET", path, null, null, null, null, false, null, List.of(), headers);
    }

    private static byte[] environmentFile(String name, String baseUrl) {
        return ("{\"format\":\"testdata.studio.environment\",\"version\":1,\"name\":\"" + name
                + "\",\"values\":{\"baseUrl\":\"" + baseUrl + "\"},\"auth\":{\"type\":\"NONE\"},\"cases\":{}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private SaveTestDataRequest save(String name, List<String> environments) {
        return new SaveTestDataRequest(specId, planId, "ok", name, "{{baseUrl}}", Map.of(), Map.of(), Map.of(), Map.of(),
                new HashMap<>(Map.of("a", 1)), 200, "JSON", null, null, null, null, null, "READY", null, null, null,
                5000L, "application/json", null, environments, null, null, null, null);
    }

    private static void assertMessage(String start, org.junit.jupiter.api.function.Executable call) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, call);
        assertTrue(refused.getMessage().startsWith(start), refused.getMessage());
    }
}
