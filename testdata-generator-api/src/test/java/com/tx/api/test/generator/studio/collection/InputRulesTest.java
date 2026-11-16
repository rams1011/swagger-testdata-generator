package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.dto.EnvironmentSummary;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.SaveEnvironmentRequest;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;
import com.tx.api.test.generator.studio.dto.TestCaseView;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.service.CaseVersionDocument;
import com.tx.api.test.generator.studio.service.SpecCatalogService;
import com.tx.api.test.generator.studio.service.TestDataStudioService;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Values the UI also checks, refused by the API with a message instead of a server error or a silent default. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=local",
                "TESTDATA_EDITION=local",
                "TESTDATA_H2_FILE=target/input-rules-test/testdata",
                "TESTDATA_H2_JDBC_URL=jdbc:h2:mem:input_rules;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
        })
class InputRulesTest {

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
    @Qualifier("studioStorage")
    private StudioStorage storage;

    private String specId;
    private String planId;

    @BeforeEach
    void newCollection() {
        specId = catalog.load(new LoadSpecRequest(null, DEFINITION, null, "http://127.0.0.1:1",
                "Rules " + UUID.randomUUID(), null, null, null, null)).specId();
        planId = studio.listPlans(specId).get(0).id();
    }

    @Test
    void anEnvironmentBaseUrlMustBeAnHttpAddress() {
        for (String bad : List.of("not a url", "ftp://x.example.com", "https://exa mple.com", "https://example.com:99999",
                "https://example.com:0", "http://")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> studio.createEnvironment(specId, new SaveEnvironmentRequest("E" + bad.hashCode(), bad, "NONE")),
                    bad);
            assertTrue(refused.getMessage().startsWith("Base URL"), refused.getMessage());
        }
        EnvironmentSummary ok = studio.createEnvironment(specId,
                new SaveEnvironmentRequest("Local", "http://localhost:8083/api", "NONE"));
        assertThrows(IllegalArgumentException.class, () -> studio.updateEnvironment(specId, ok.id(),
                new SaveEnvironmentRequest("Local", "not a url", null)));
        studio.updateEnvironment(specId, ok.id(), new SaveEnvironmentRequest("Local", "{{host}}/api", null));
    }

    @Test
    void longNamesAreRefusedWithAMessage() {
        String longName = "x".repeat(256);
        IllegalArgumentException environment = assertThrows(IllegalArgumentException.class,
                () -> studio.createEnvironment(specId, new SaveEnvironmentRequest(longName, "http://127.0.0.1:1", "NONE")));
        assertTrue(environment.getMessage().contains("at most 255"), environment.getMessage());

        IllegalArgumentException testName = assertThrows(IllegalArgumentException.class,
                () -> studio.create(save(longName, null, 5000L, null)));
        assertTrue(testName.getMessage().startsWith("Test name can be at most 255"), testName.getMessage());

        IllegalArgumentException group = assertThrows(IllegalArgumentException.class,
                () -> studio.create(save("Grouped", "g".repeat(129), 5000L, null)));
        assertTrue(group.getMessage().startsWith("Group can be at most 128"), group.getMessage());

        studio.create(save("x".repeat(255), "g".repeat(128), 5000L, null));
    }

    @Test
    void maxResponseTimeIsFromOneMillisecondToFiveMinutes() {
        for (long bad : new long[] {-5, 0, 300_001}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> studio.create(save("Timed", null, bad, null)));
            assertTrue(refused.getMessage().startsWith("Max response time"), refused.getMessage());
        }
        assertEquals(300_000L, studio.create(save("Slow", null, 300_000L, null)).maxResponseTimeMs());
    }

    @Test
    void anIgnoreFieldMustBeAJsonPathAndTheMessageIsReadable() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> studio.create(save("Ignoring", null, 5000L, "$.id\n$.[")));
        assertTrue(refused.getMessage().startsWith("Ignore field $.[ is not a valid JSONPath"), refused.getMessage());
        assertFalse(refused.getMessage().contains("java."), refused.getMessage());
        studio.create(save("Ignoring", null, 5000L, "$.id\n$..updatedAt\n$.items[*].id"));
    }

    @Test
    void theLastEnvironmentCannotBeDeleted() {
        List<EnvironmentSummary> environments = studio.listEnvironments(specId);
        assertEquals(1, environments.size());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> studio.deleteEnvironment(specId, environments.get(0).id()));
        assertTrue(refused.getMessage().startsWith("A collection needs at least one environment"), refused.getMessage());

        EnvironmentSummary second = studio.createEnvironment(specId,
                new SaveEnvironmentRequest("Second", "http://127.0.0.1:1", "NONE"));
        studio.deleteEnvironment(specId, second.id());
    }

    @Test
    void baseUrlIsNotAVariableName() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> StudioVariables.normalize(List.of(new StudioVariable("baseUrl", "https://x", false)), "Global"));
        assertTrue(refused.getMessage().contains("reserved for the environment's base URL"), refused.getMessage());
    }

    @Test
    void anUnchangedCaseIsTheSameCase() {
        TestCaseView created = studio.create(save("Same", null, 5000L, null));
        StudioTestCase stored = storage.findTestCase(created.id()).orElseThrow();
        StudioTestCase again = storage.findTestCase(created.id()).orElseThrow();
        assertTrue(CaseVersionDocument.sameCase(stored, again));
        again.setTestName("Different");
        assertFalse(CaseVersionDocument.sameCase(stored, again));
    }

    private SaveTestDataRequest save(String name, String group, Long maxResponseTimeMs, String ignoreFields) {
        return new SaveTestDataRequest(specId, planId, "ok", name, "{{baseUrl}}", Map.of(), Map.of(), Map.of(), Map.of(),
                new HashMap<>(Map.of("a", 1)), 200, "JSON", "{\"a\":1}", null, ignoreFields, null, null, "READY", group,
                null, null, maxResponseTimeMs, "application/json", null, null, null, null, null, null);
    }
}
