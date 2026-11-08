package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.api.ConflictException;
import com.tx.api.test.generator.studio.dto.EnvironmentSummary;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.SaveEnvironmentRequest;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;
import com.tx.api.test.generator.studio.dto.TestCaseView;
import com.tx.api.test.generator.studio.service.SpecCatalogService;
import com.tx.api.test.generator.studio.service.TestDataStudioService;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Environment links by ID, revision-based conflict checks and Enabled as a boolean, through the real service. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=local",
                "TESTDATA_EDITION=local",
                "TESTDATA_H2_FILE=target/integrity-rules-test/testdata",
                "TESTDATA_H2_JDBC_URL=jdbc:h2:mem:integrity_rules;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
        })
class IntegrityRulesTest {

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
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.tx.api.test.generator.studio.service.RunHistoryService runHistory;

    private String specId;
    private String planId;
    private EnvironmentSummary staging;
    private EnvironmentSummary production;

    @BeforeEach
    void newCollection() {
        specId = catalog.load(new LoadSpecRequest(null, DEFINITION, null, "http://127.0.0.1:1",
                "Integrity " + UUID.randomUUID(), null, null, null, null)).specId();
        planId = studio.listPlans(specId).get(0).id();
        staging = studio.createEnvironment(specId, new SaveEnvironmentRequest("Staging", "http://127.0.0.1:1", "NONE"));
        production = studio.createEnvironment(specId, new SaveEnvironmentRequest("Production", "http://127.0.0.1:1", "NONE"));
    }

    @Test
    void renamingAnEnvironmentKeepsItsCases() {
        TestCaseView testCase = studio.create(save("Scoped", null, List.of("Staging"), null));

        studio.updateEnvironment(specId, staging.id(), new SaveEnvironmentRequest("Pre-production", null, null));

        assertEquals(List.of("Pre-production"), studio.get(testCase.id(), null).environments());
        assertEquals(List.of("Scoped"), names(studio.list(specId, planId, null, staging.id())));
    }

    @Test
    void deletingAnEnvironmentRemovesCasesOnlyInItAndUnlinksTheRest() {
        TestCaseView onlyStaging = studio.create(save("Only staging", null, List.of("Staging"), null));
        TestCaseView both = studio.create(save("Both", null, List.of("Staging", "Production"), null));
        TestCaseView everywhere = studio.create(save("Everywhere", null, null, null));

        studio.deleteEnvironment(specId, staging.id());

        assertThrows(NoSuchElementException.class, () -> studio.get(onlyStaging.id(), null));
        assertEquals(List.of("Production"), studio.get(both.id(), null).environments());
        assertNull(studio.get(everywhere.id(), null).environments(), "still in every environment");
    }

    @Test
    void aCaseCannotNameAnEnvironmentThatDoesNotExist() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> studio.create(save("Lost", null, List.of("Nowhere"), null)));
        assertTrue(refused.getMessage().contains("Nowhere"), refused.getMessage());
    }

    @Test
    void importCreatesTheEnvironmentsACollectionNames() {
        List<String> before = environmentNames();

        CollectionImporter.ensureEnvironments(storage, specId, List.of("staging", "QA"));

        List<String> expected = new java.util.ArrayList<>(before);
        expected.add("QA");
        assertEquals(expected.stream().sorted().toList(), environmentNames(), "staging matched regardless of case");
    }

    private List<String> environmentNames() {
        return studio.listEnvironments(specId).stream().map(EnvironmentSummary::name).sorted().toList();
    }

    @Test
    void aSaveBasedOnAnOlderRevisionIsRefused() {
        TestCaseView created = studio.create(save("First", null, null, null));
        TestCaseView saved = studio.update(created.id(), save("Second", created.revision(), null, null));
        assertEquals(created.revision() + 1, saved.revision());

        assertThrows(ConflictException.class,
                () -> studio.update(created.id(), save("Lost update", created.revision(), null, null)),
                "two saves from the same revision: the second is refused, even within the same millisecond");
        assertEquals("Second", studio.get(created.id(), null).testName());
    }

    @Test
    void savingAnEnvironmentDoesNotChangeTheCollection() {
        studio.applyEnvironment(specId, staging.id());
        long revision = catalog.getSummary(specId).revision();

        studio.updateEnvironment(specId, staging.id(), new SaveEnvironmentRequest("Staging", null, null,
                List.of(new com.tx.api.test.generator.studio.collection.StudioVariable("token", "abc", false))));
        studio.applyEnvironment(specId, staging.id());
        catalog.rename(specId, new com.tx.api.test.generator.studio.dto.SaveSpecRequest(
                null, null, catalog.getSummary(specId).baseUrl(), null, null));

        assertEquals(revision, catalog.getSummary(specId).revision(),
                "re-applying the same base URL, or adding a variable, writes nothing to the collection");

        studio.updateEnvironment(specId, staging.id(), new SaveEnvironmentRequest("Staging", "http://127.0.0.1:2", null));
        studio.applyEnvironment(specId, staging.id());
        assertEquals(revision + 1, catalog.getSummary(specId).revision(), "a different base URL is a change");
    }

    @Test
    void collectionVariablesBelongToOneCollectionAndLeaveItsRevisionAlone() {
        long revision = catalog.getSummary(specId).revision();
        studio.saveCollectionVariables(specId, new com.tx.api.test.generator.studio.dto.CollectionVariables(List.of(
                new StudioVariable(" tenant ", "acme", false), new StudioVariable("apiKey", "", true))));

        assertEquals(List.of(new StudioVariable("tenant", "acme", false), new StudioVariable("apiKey", null, true)),
                studio.getCollectionVariables(specId).variables());
        assertEquals(revision, catalog.getSummary(specId).revision());
        String other = catalog.load(new LoadSpecRequest(null, DEFINITION, null, "http://127.0.0.1:1",
                "Other " + UUID.randomUUID(), null, null, null, null)).specId();
        assertTrue(studio.getCollectionVariables(other).variables().isEmpty());

        catalog.delete(specId);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM td_collection_variables WHERE spec_id = ?",
                Integer.class, specId));
    }

    @Test
    void aCaseWithDataRowsRunsOncePerRowAndSendCanRunOneRow() {
        SaveTestDataRequest base = save("Rows", null, null, null);
        SaveTestDataRequest withRows = new SaveTestDataRequest(base.specId(), base.planId(), base.operationKey(),
                base.testName(), base.baseUrl(), base.pathParams(), Map.of("tier", "{{tier}}"), base.headers(),
                base.cookieParams(), base.requestBody(), base.statusCode(), base.responseType(), base.expectedResponse(),
                base.responseMatch(), base.ignoreFields(), base.auth(), base.enabled(), base.lifecycle(),
                base.testGroup(), base.jsonPathAssertions(), base.extracts(), base.maxResponseTimeMs(),
                base.contentType(), base.environmentId(), base.environments(), null, null, null, null,
                List.of(Map.of("$name", "Gold", "tier", "GOLD"), Map.of("tier", "SILVER")));
        TestCaseView created = studio.create(withRows);
        assertEquals(2, created.dataRows().size());

        var all = studio.run(new com.tx.api.test.generator.studio.dto.RunTestRequest(created.id(), null, null, null));
        assertEquals(List.of("Gold", "Row 2"), all.iterations().stream()
                .map(com.tx.api.test.generator.studio.dto.RunTestResult::rowLabel).toList());
        assertTrue(all.iterations().get(0).url().contains("tier=GOLD"), all.iterations().get(0).url());
        assertTrue(all.iterations().get(1).url().contains("tier=SILVER"), all.iterations().get(1).url());
        String runId = runHistory.list(specId, null, null, created.id(), null, null, 1).get(0).runId();
        assertEquals("failed", runHistory.get(runId).results().get(0).outcome(),
                "rows that fail make the case failed in history, not an error");

        var one = studio.run(new com.tx.api.test.generator.studio.dto.RunTestRequest(created.id(), null, null, null,
                null, 2));
        assertEquals(1, one.iterations().size());
        assertEquals("Row 2", one.iterations().get(0).rowLabel());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> studio.update(created.id(),
                new SaveTestDataRequest(base.specId(), base.planId(), base.operationKey(), base.testName(),
                        base.baseUrl(), base.pathParams(), base.queryParams(), base.headers(), base.cookieParams(),
                        base.requestBody(), base.statusCode(), base.responseType(), base.expectedResponse(),
                        base.responseMatch(), base.ignoreFields(), base.auth(), base.enabled(), "READY",
                        base.testGroup(), base.jsonPathAssertions(), base.extracts(), base.maxResponseTimeMs(),
                        base.contentType(), null, null, studio.get(created.id(), null).revision(), null, null, null,
                        List.of(Map.of("$status", 42)))));
        assertTrue(refused.getMessage().contains("100 to 599"), refused.getMessage());
    }

    @Test
    void groupAuthAndCollectionHeadersReachTheRequestAndTheExport() {
        studio.updateEnvironment(specId, staging.id(), new SaveEnvironmentRequest("Staging", null, null,
                List.of(new StudioVariable("adminToken", "t-admin", false))));
        studio.saveSharedSettings(specId, new SharedSettings(null, Map.of("X-Tenant", "acme"), Map.of("Admin",
                new SharedSettings.Level(new com.tx.api.test.generator.studio.dto.RunAuth("BEARER", null, null,
                        "{{adminToken}}", null, null, null, null, null, null, null, null), null))));
        long revision = catalog.getSummary(specId).revision();
        SaveTestDataRequest base = save("Admin call", null, null, null);
        TestCaseView created = studio.create(new SaveTestDataRequest(base.specId(), base.planId(), base.operationKey(),
                base.testName(), base.baseUrl(), base.pathParams(), base.queryParams(), Map.of("Accept", "text/plain"),
                base.cookieParams(), base.requestBody(), base.statusCode(), base.responseType(), base.expectedResponse(),
                base.responseMatch(), base.ignoreFields(), base.auth(), base.enabled(), base.lifecycle(), "Admin",
                base.jsonPathAssertions(), base.extracts(), base.maxResponseTimeMs(), base.contentType(), null, null,
                null, null, null, null));

        var result = studio.run(new com.tx.api.test.generator.studio.dto.RunTestRequest(created.id(), null, null,
                staging.id()));

        assertTrue(String.valueOf(result.requestHeaders().get("Authorization")).startsWith("Bearer "),
                "the group's bearer token is sent (results mask its value): " + result.requestHeaders());
        assertEquals("acme", result.requestHeaders().get("X-Tenant"));
        assertEquals("text/plain", result.requestHeaders().get("Accept"));
        assertEquals(null, studio.get(created.id(), null).auth(), "the case still has no auth of its own");
        assertEquals(revision, catalog.getSummary(specId).revision(), "saving shared settings leaves the collection alone");
        assertEquals("acme", CollectionMapper.readFrom(storage, specId, planId).settings().headerMap().get("X-Tenant"));
    }

    @Test
    void enabledIsStoredAsABoolean() {
        TestCaseView disabled = studio.create(save("Off", null, null, "N"));

        assertEquals("N", studio.get(disabled.id(), null).enabled());
        assertFalse(jdbc.queryForObject("SELECT enabled FROM td_test_case WHERE test_case_id = ?", Boolean.class,
                disabled.id()));
    }

    private static List<String> names(List<TestCaseView> cases) {
        return cases.stream().map(TestCaseView::testName).toList();
    }

    private SaveTestDataRequest save(String name, Long expectedRevision, List<String> environments, String enabled) {
        return new SaveTestDataRequest(specId, planId, "ok", name, "{{baseUrl}}", Map.of(), Map.of(), Map.of(), Map.of(),
                new HashMap<>(Map.of("a", 1)), 200, "JSON", null, null, null, null, enabled, "READY", null, null, null,
                5000L, "application/json", null, environments, expectedRevision, null, null, null);
    }
}
