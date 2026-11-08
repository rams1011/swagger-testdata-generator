package com.tx.api.test.generator.studio.service;

import com.sun.net.httpserver.HttpServer;
import com.tx.api.test.generator.studio.api.ConflictException;
import com.tx.api.test.generator.studio.dto.CollectionExport;
import com.tx.api.test.generator.studio.dto.ExportRequest;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.RequestFileView;
import com.tx.api.test.generator.studio.dto.RunPlanRequest;
import com.tx.api.test.generator.studio.dto.RunPlanResult;
import com.tx.api.test.generator.studio.dto.RunTestRequest;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;
import com.tx.api.test.generator.studio.dto.TestCaseView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A draft of a published case must not reach anything that runs or ships until it is published. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=local",
                "TESTDATA_EDITION=local",
                "TESTDATA_RUN_ALLOW_PRIVATE_NETWORKS=true",
                "TESTDATA_H2_FILE=target/published-case-draft-test/testdata",
                "TESTDATA_H2_JDBC_URL=jdbc:h2:mem:published_case_draft;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
        })
class PublishedCaseDraftTest {

    private static final String DEFINITION = """
            {"openapi":"3.0.3","info":{"title":"T","version":"1"},"paths":{"/ok":{"post":{"operationId":"ok",
            "requestBody":{"content":{"application/json":{"schema":{"type":"object"}}}},
            "responses":{"200":{"description":"OK"}}}}}}
            """;
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static HttpServer target;

    @Autowired
    private TestDataStudioService studio;
    @Autowired
    private SpecCatalogService catalog;
    @Autowired
    private JdbcTemplate jdbc;

    private String specId;
    private String planId;
    private String environmentId;

    @BeforeAll
    static void startTarget() throws Exception {
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            REQUESTS.incrementAndGet();
            byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();
    }

    @AfterAll
    static void stopTarget() {
        target.stop(0);
    }

    @BeforeEach
    void newCollection() {
        specId = catalog.load(new LoadSpecRequest(null, DEFINITION, null,
                "http://127.0.0.1:" + target.getAddress().getPort(), "Drafts " + UUID.randomUUID(), null, null, null, null))
                .specId();
        planId = studio.listPlans(specId).get(0).id();
        environmentId = studio.listEnvironments(specId).get(0).id();
    }

    @Test
    void saveDraftLeavesEverythingThatRunsOrShipsOnThePublishedVersion() {
        TestCaseView published = publishedCase("Published name", 200);

        TestCaseView saved = saveDraft(published, "Draft name", 500, null);

        assertEquals("READY", saved.lifecycle());
        assertEquals("Published name", saved.testName());
        assertEquals(published.updatedAt(), saved.updatedAt(), "the published case isn't written at all");
        assertTrue(saved.hasDraft());
        assertEquals("Draft name", saved.draft().testName());
        assertEquals(500, saved.draft().statusCode());

        assertRunsAndShipsAs(published.id(), "Published name", true);
        TestCaseView listed = studio.list(specId, planId, null, environmentId).get(0);
        assertTrue(listed.hasDraft());
        assertEquals("Published name", listed.testName());
        assertEquals("Draft name", listed.draft().testName());
    }

    @Test
    void publishReplacesThePublishedVersionAndClearsTheDraft() {
        TestCaseView published = publishedCase("Published name", 200);
        TestCaseView saved = saveDraft(published, "Draft name", 500, null);

        TestCaseView after = studio.update(published.id(),
                request("Draft name", 500, "READY", saved.draft().revision(), null, null));

        assertEquals("Draft name", after.testName());
        assertFalse(after.hasDraft());
        assertNull(after.draft());
        assertRunsAndShipsAs(published.id(), "Draft name", false);
    }

    @Test
    void discardDropsTheDraftAndLeavesThePublishedVersion() {
        TestCaseView published = publishedCase("Published name", 200);
        saveDraft(published, "Draft name", 500, null);

        TestCaseView after = studio.discardDraft(published.id(), environmentId);

        assertFalse(after.hasDraft());
        assertEquals(published.updatedAt(), after.updatedAt());
        assertRunsAndShipsAs(published.id(), "Published name", true);
    }

    @Test
    void aNeverPublishedDraftStaysOutOfPlansAndExport() {
        TestCaseView draft = studio.create(request("New", 200, "DRAFT", null, null, null));

        assertEquals("DRAFT", draft.lifecycle());
        assertFalse(draft.hasDraft());
        RunPlanResult plan = runPlan();
        assertEquals(0, plan.passed());
        assertEquals(1, plan.skipped());
        assertThrows(IllegalStateException.class, () -> export());
        assertEquals(List.of(), caseNamesCiWouldRead());

        studio.update(draft.id(), request("New", 200, "READY", null, null, null));
        assertEquals(1, runPlan().passed());
        assertEquals(1, export().caseCount());
    }

    @Test
    void anEnableToggleLeavesTheDraftAndDoesNotBlockPublishing() {
        TestCaseView published = publishedCase("Published name", 200);
        TestCaseView saved = saveDraft(published, "Draft name", 200, null);

        TestCaseView toggled = studio.update(published.id(),
                new SaveTestDataRequest(specId, planId, "ok", "Published name", null, null, null, null, null, null, 200,
                        null, null, null, null, null, "N", "READY", null, null, null, null, null, environmentId, null,
                        null, null, null, true));

        assertEquals("N", toggled.enabled());
        assertEquals("Published name", toggled.testName());
        assertTrue(toggled.hasDraft());
        assertEquals("N", toggled.draft().enabled(), "the draft doesn't hold its own copy of the setting");

        TestCaseView after = studio.update(published.id(),
                request("Draft name", 200, "READY", saved.draft().revision(), null, null));
        assertEquals("Draft name", after.testName());
    }

    @Test
    void aSecondEditorCannotOverwriteDraftChangesTheyHaveNotSeen() throws Exception {
        TestCaseView published = publishedCase("Published name", 200);
        TestCaseView first = saveDraft(published, "First", 200, null);

        assertThrows(ConflictException.class, () -> saveDraft(published, "Second", 200, null),
                "didn't know a draft existed");
        assertThrows(ConflictException.class,
                () -> studio.update(published.id(), request("Mine", 200, "READY", null, null, null)),
                "publishing would throw away changes they haven't seen");

        Thread.sleep(5);
        TestCaseView second = saveDraft(published, "Second", 200, first.draft().revision());
        assertEquals("Second", second.draft().testName());
        assertThrows(ConflictException.class,
                () -> saveDraft(published, "Third", 200, first.draft().revision()), "out of date");

        TestCaseView forced = studio.update(published.id(), request("Forced", 200, "READY", null, true, null));
        assertEquals("Forced", forced.testName());
        assertFalse(forced.hasDraft());
    }

    @Test
    void publishingADraftWarnsWhenThePublishedVersionMovedOn() throws Exception {
        TestCaseView published = publishedCase("Published name", 200);
        TestCaseView saved = saveDraft(published, "Draft name", 200, null);

        Thread.sleep(5);
        studio.update(published.id(), request("Changed underneath", 200, "", null, null, null));

        ConflictException conflict = assertThrows(ConflictException.class, () -> studio.update(published.id(),
                request("Draft name", 200, "READY", saved.draft().revision(), null, null)));
        assertTrue(conflict.getMessage().contains("published version changed"));
        assertEquals("Changed underneath", studio.get(published.id(), environmentId).testName());
    }

    @Test
    void aFileUploadedForADraftWaitsForPublish() throws Exception {
        TestCaseView published = publishedCase("Published name", 200);

        RequestFileView file = studio.saveRequestFile(published.id(),
                new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1, 2, 3}), "photo");

        assertEquals(published.updatedAt(), studio.get(published.id(), environmentId).updatedAt(),
                "the upload doesn't touch the published case");

        Object bodyWithFile = bodyWithFile(file, published);
        TestCaseView saved = studio.update(published.id(), withBody(
                request("Published name", 200, "DRAFT", null, null, null), bodyWithFile));
        assertEquals(0, export().fileCount(), "a draft's file stays out of the export");

        studio.update(published.id(), withBody(
                request("Published name", 200, "READY", saved.draft().revision(), null, null), bodyWithFile));
        assertEquals(1, export().fileCount());
    }

    @Test
    void discardingADraftRemovesTheFileOnlyItUsed() throws Exception {
        TestCaseView published = publishedCase("Published name", 200);
        RequestFileView file = studio.saveRequestFile(published.id(),
                new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1, 2, 3}), "photo");
        studio.update(published.id(), withBody(request("Published name", 200, "DRAFT", null, null, null),
                bodyWithFile(file, published)));

        studio.discardDraft(published.id(), environmentId);

        assertThrows(java.util.NoSuchElementException.class,
                () -> studio.requireRequestFile(published.id(), file.fileId()));
    }

    @Test
    void importingACaseReplacesItAndEndsItsDraft() {
        TestCaseView published = publishedCase("Published name", 200);
        CollectionExport exported = export();
        saveDraft(published, "Draft name", 200, null);

        studio.importCollection(exported.content(), exported.fileName(), specId, environmentId);

        assertFalse(studio.get(published.id(), environmentId).hasDraft());
    }

    @Test
    void deletingACaseDeletesItsDraft() {
        TestCaseView published = publishedCase("Published name", 200);
        saveDraft(published, "Draft name", 200, null);

        studio.delete(published.id());

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM td_test_case_draft WHERE test_case_id = ?", Integer.class, published.id()));
    }

    @Test
    void eachPublishedStateIsKeptAndDraftsAreNot() {
        TestCaseView published = publishedCase("Version one", 200);
        assertEquals(1, versions(published.id()));

        TestCaseView saved = saveDraft(published, "Version two", 200, null);
        assertEquals(1, versions(published.id()), "saving a draft publishes nothing");

        studio.update(published.id(), request("Version two", 200, "READY", saved.draft().revision(), null, null));
        assertEquals(2, versions(published.id()));
        String latest = jdbc.queryForObject("""
                SELECT document_json FROM td_test_case_version WHERE test_case_id = ?
                 ORDER BY published_at DESC, document_json DESC LIMIT 1
                """, String.class, published.id());
        assertNotNull(latest);
        assertTrue(latest.contains("Version"));
    }

    @Test
    void aNeverPublishedCasesExtractsAreNotAvailableToOtherCases() {
        TestCaseView producer = studio.create(chained("Producer", "DRAFT", "userId=$.id", null, null));
        TestCaseView consumer = studio.create(chained("Consumer", "READY", "NA", "{{userId}}", null));
        REQUESTS.set(0);

        RunTestResult single = studio.run(new RunTestRequest(consumer.id(), null, null, environmentId, null));
        assertFalse(single.passed());
        assertTrue(single.error().startsWith("Unresolved variable {{userId}}"), single.error());
        assertEquals(0, REQUESTS.get(), "neither the draft nor the case using it was sent");

        RunPlanResult plan = runPlan();
        assertEquals(0, plan.passed());
        assertEquals(0, REQUESTS.get(), "a plan doesn't send the draft either");

        studio.update(producer.id(), chained("Producer", "READY", "userId=$.id", null, null));
        assertTrue(studio.run(new RunTestRequest(consumer.id(), null, null, environmentId, null)).passed(),
                "published, it supplies the value");
    }

    @Test
    void aDraftOfAPublishedCaseOnlyOffersThePublishedExtracts() {
        TestCaseView producer = studio.create(chained("Producer", "READY", "userId=$.id", null, null));
        TestCaseView usesPublished = studio.create(chained("Uses published", "READY", "NA", "{{userId}}", null));
        TestCaseView usesDraft = studio.create(chained("Uses draft", "READY", "NA", "{{draftOnly}}", null));
        TestCaseView saved = studio.update(producer.id(), chained("Producer", "DRAFT", "draftOnly=$.id", null, null));

        assertTrue(studio.run(new RunTestRequest(usesPublished.id(), null, null, environmentId, null)).passed(),
                "the published extract still works, though the draft removes it");
        RunTestResult blocked = studio.run(new RunTestRequest(usesDraft.id(), null, null, environmentId, null));
        assertFalse(blocked.passed());
        assertTrue(blocked.error().startsWith("Unresolved variable {{draftOnly}}"), blocked.error());

        studio.update(producer.id(), withExpectedDraft(
                chained("Producer", "READY", "draftOnly=$.id", null, null), saved.draft().revision()));
        assertTrue(studio.run(new RunTestRequest(usesDraft.id(), null, null, environmentId, null)).passed(),
                "published, the new extract is available");
        assertFalse(studio.run(new RunTestRequest(usesPublished.id(), null, null, environmentId, null)).passed(),
                "and the old one has gone");
    }

    private void assertRunsAndShipsAs(String caseId, String name, boolean passes) {
        assertEquals(passes, studio.run(new RunTestRequest(caseId, null, null, environmentId, null)).passed(), "single run");
        RunPlanResult plan = runPlan();
        assertEquals(passes ? 1 : 0, plan.passed(), "plan");
        assertEquals(name, plan.results().get(0).testName());
        assertEquals(1, export().caseCount());
        assertTrue(new String(exportJson(), StandardCharsets.UTF_8).contains(name), "export has " + name);
        assertEquals(List.of(name), caseNamesCiWouldRead());
    }

    /** The test that api-automation's JdbcTestDataProvider applies when it reads cases straight from the database. */
    private List<String> caseNamesCiWouldRead() {
        return jdbc.queryForList("""
                SELECT c.test_name FROM td_test_case c
                 WHERE c.spec_id = ? AND UPPER(COALESCE(c.lifecycle, 'READY')) <> 'DRAFT'
                """, String.class, specId);
    }

    private int versions(String caseId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM td_test_case_version WHERE test_case_id = ?", Integer.class, caseId);
    }

    private RunPlanResult runPlan() {
        return studio.runPlan(new RunPlanRequest(specId, planId, null, environmentId));
    }

    private CollectionExport export() {
        return studio.exportCollection(new ExportRequest(specId, planId, environmentId));
    }

    private byte[] exportJson() {
        return export().content();
    }

    private TestCaseView publishedCase(String name, int status) {
        return studio.create(request(name, status, "READY", null, null, null));
    }

    private TestCaseView saveDraft(TestCaseView published, String name, int status, Long expectedDraft) {
        return studio.update(published.id(), request(name, status, "DRAFT", expectedDraft, null, null));
    }

    private SaveTestDataRequest request(String name, int status, String lifecycle, Long expectedDraft, Boolean force,
                                        Boolean keepDraft) {
        return new SaveTestDataRequest(specId, planId, "ok", name, "{{baseUrl}}", Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("a", 1), status, "JSON", null, null, null, null, "Y", lifecycle, null, null, null, 5000L,
                "application/json", environmentId, null, null, force, expectedDraft, keepDraft);
    }

    private SaveTestDataRequest chained(String name, String lifecycle, String extracts, String headerValue,
                                        Long expectedDraft) {
        Map<String, String> headers = headerValue == null ? Map.of() : Map.of("X-User", headerValue);
        return new SaveTestDataRequest(specId, planId, "ok", name, "{{baseUrl}}", Map.of(), Map.of(), headers, Map.of(),
                new HashMap<>(Map.of("a", 1)), 200, "JSON", null, null, null, null, "Y", lifecycle, null, null, extracts,
                5000L, "application/json", environmentId, null, null, null, expectedDraft, null);
    }

    private static SaveTestDataRequest withExpectedDraft(SaveTestDataRequest source, Long expectedDraft) {
        return new SaveTestDataRequest(source.specId(), source.planId(), source.operationKey(), source.testName(),
                source.baseUrl(), source.pathParams(), source.queryParams(), source.headers(), source.cookieParams(),
                source.requestBody(), source.statusCode(), source.responseType(), source.expectedResponse(),
                source.responseMatch(), source.ignoreFields(), source.auth(), source.enabled(), source.lifecycle(),
                source.testGroup(), source.jsonPathAssertions(), source.extracts(), source.maxResponseTimeMs(),
                source.contentType(), source.environmentId(), source.environments(), source.expectedRevision(),
                source.forceOverwrite(), expectedDraft, source.keepDraft());
    }

    /** Mutable, like a body that came in as JSON. */
    private static Object bodyWithFile(RequestFileView file, TestCaseView testCase) {
        Map<String, Object> ref = new HashMap<>();
        ref.put("fileId", file.fileId());
        ref.put("testCaseId", testCase.id());
        ref.put("fileName", "photo.png");
        Map<String, Object> body = new HashMap<>();
        body.put("photo", ref);
        return body;
    }

    private static SaveTestDataRequest withBody(SaveTestDataRequest source, Object body) {
        return new SaveTestDataRequest(source.specId(), source.planId(), source.operationKey(), source.testName(),
                source.baseUrl(), source.pathParams(), source.queryParams(), source.headers(), source.cookieParams(), body,
                source.statusCode(), source.responseType(), source.expectedResponse(), source.responseMatch(),
                source.ignoreFields(), source.auth(), source.enabled(), source.lifecycle(), source.testGroup(),
                source.jsonPathAssertions(), source.extracts(), source.maxResponseTimeMs(), "MULTIPART",
                source.environmentId(), source.environments(), source.expectedRevision(), source.forceOverwrite(),
                source.expectedDraftRevision(), source.keepDraft());
    }
}
