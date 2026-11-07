package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.api.ConflictException;
import com.tx.api.test.generator.studio.dto.CaseVersionView;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.RequestFileView;
import com.tx.api.test.generator.studio.dto.RestoreCaseVersionRequest;
import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;
import com.tx.api.test.generator.studio.dto.TestCaseView;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=local",
                "TESTDATA_EDITION=local",
                "TESTDATA_H2_FILE=target/case-version-restore-test/testdata",
                "TESTDATA_H2_JDBC_URL=jdbc:h2:mem:case_version_restore;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
        })
class CaseVersionRestoreTest {

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
    @org.springframework.beans.factory.annotation.Qualifier("studioStorage")
    private StudioStorage storage;

    private String specId;
    private String planId;
    private String environmentId;

    @BeforeEach
    void newCollection() {
        specId = catalog.load(new LoadSpecRequest(null, DEFINITION, null, "http://127.0.0.1:1",
                "Versions " + UUID.randomUUID(), null, null, null, null)).specId();
        planId = studio.listPlans(specId).get(0).id();
        environmentId = studio.listEnvironments(specId).get(0).id();
    }

    @Test
    void restoringPublishesTheOldContentAsANewVersion() throws Exception {
        TestCaseView first = studio.create(save("First", 200, Map.of("count", 1), null, null, null, "READY", null));
        TestCaseView second = publish(first, save("Second", 500, Map.of("count", 2), null, null, null, "READY", null));

        List<CaseVersionView> before = studio.caseVersions(first.id());
        assertEquals(2, before.size());
        assertTrue(before.get(0).current());
        assertEquals("Second", before.get(0).document().get("testName"));
        assertFalse(before.get(1).current());

        Thread.sleep(5);
        TestCaseView restored = restore(second, before.get(1).id(), null);

        assertEquals("First", restored.testName());
        assertEquals(200, restored.statusCode());
        assertEquals(1, ((Number) ((Map<?, ?>) restored.requestBody()).get("count")).intValue());
        assertEquals("READY", restored.lifecycle());

        List<CaseVersionView> after = studio.caseVersions(first.id());
        assertEquals(3, after.size(), "what it replaced can be restored too");
        assertTrue(after.get(0).current());
        assertEquals("First", after.get(0).document().get("testName"));
        assertEquals("Second", after.get(1).document().get("testName"));
    }

    @Test
    void settingsTheVersionLeftEmptyComeBackEmpty() throws Exception {
        TestCaseView first = studio.create(save("Case", 200, Map.of(), null, null, null, "READY", null));
        RunAuth none = new RunAuth(RunAuth.NONE, null, null, null, null, null, null, null, null, null, null, null);
        TestCaseView changed = publish(first, save("Case", 200, Map.of(), "lenient", "$.id", none, "READY", null));
        assertEquals("lenient", changed.responseMatch());
        assertEquals("$.id", changed.ignoreFields());
        assertEquals(RunAuth.NONE, changed.auth().normalizedType());

        Thread.sleep(5);
        TestCaseView restored = restore(changed, studio.caseVersions(first.id()).get(1).id(), null);

        assertEquals("strict", restored.responseMatch());
        assertNull(restored.ignoreFields());
        assertNull(restored.auth());
    }

    @Test
    void enabledIsLeftAsItIsNowAndIsNotAVersion() throws Exception {
        TestCaseView first = studio.create(save("First", 200, Map.of(), null, null, null, "READY", null));
        TestCaseView second = publish(first, save("Second", 200, Map.of(), null, null, null, "READY", null));
        TestCaseView disabled = publish(second, save("Second", 200, Map.of(), null, null, null, "READY", "N"));
        assertEquals("N", disabled.enabled());

        List<CaseVersionView> versions = studio.caseVersions(first.id());
        assertEquals(2, versions.size(), "only Enabled changed");
        assertTrue(versions.get(0).current());

        Thread.sleep(5);
        TestCaseView restored = restore(disabled, versions.get(1).id(), null);
        assertEquals("First", restored.testName());
        assertEquals("N", restored.enabled());
    }

    @Test
    void restoringEndsDraftChangesButOnlyIfTheyWereSeen() throws Exception {
        TestCaseView first = studio.create(save("First", 200, Map.of(), null, null, null, "READY", null));
        TestCaseView second = publish(first, save("Second", 200, Map.of(), null, null, null, "READY", null));
        TestCaseView drafted = studio.update(first.id(), save("Draft", 200, Map.of(), null, null, null, "DRAFT", null));
        String oldVersion = studio.caseVersions(first.id()).get(1).id();

        assertThrows(ConflictException.class, () -> studio.restoreCaseVersion(first.id(), oldVersion,
                new RestoreCaseVersionRequest(second.updatedAt().toString(), null, environmentId)),
                "these draft changes haven't been seen");
        assertEquals("Second", studio.get(first.id(), environmentId).testName());
        assertTrue(studio.get(first.id(), environmentId).hasDraft());

        Thread.sleep(5);
        TestCaseView restored = restore(drafted, oldVersion, drafted.draft().updatedAt().toString());

        assertEquals("First", restored.testName());
        assertFalse(restored.hasDraft());
        assertNull(restored.draft());
    }

    @Test
    void restoringAStaleCopyIsAConflict() throws Exception {
        TestCaseView first = studio.create(save("First", 200, Map.of(), null, null, null, "READY", null));
        TestCaseView second = publish(first, save("Second", 200, Map.of(), null, null, null, "READY", null));
        String oldVersion = studio.caseVersions(first.id()).get(1).id();
        publish(second, save("Third", 200, Map.of(), null, null, null, "READY", null));

        assertThrows(ConflictException.class, () -> restore(second, oldVersion, null));
        assertEquals("Third", studio.get(first.id(), environmentId).testName());
    }

    @Test
    void aVersionWhoseFileIsGoneCannotBeRestored() throws Exception {
        TestCaseView first = studio.create(save("First", 200, Map.of(), null, null, null, "READY", null));
        RequestFileView file = studio.saveRequestFile(first.id(),
                new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1, 2, 3}), "photo");
        TestCaseView current = studio.get(first.id(), environmentId);
        Map<String, Object> ref = new HashMap<>();
        ref.put("fileId", file.fileId());
        ref.put("testCaseId", first.id());
        ref.put("fileName", "photo.png");
        TestCaseView withFile = publish(current, save("With file", 200, Map.of("photo", ref), null, null, null, "READY", null));
        TestCaseView withoutFile = publish(withFile, save("Without file", 200, Map.of(), null, null, null, "READY", null));
        String versionWithFile = studio.caseVersions(first.id()).get(1).id();
        assertEquals("With file", studio.caseVersions(first.id()).get(1).document().get("testName"));

        assertFalse(studio.caseVersions(first.id()).get(1).missingFiles(), "the file is still there");
        Thread.sleep(5);
        TestCaseView restored = restore(withoutFile, versionWithFile, null);
        assertEquals("With file", restored.testName());
        Thread.sleep(5);
        TestCaseView away = publish(restored, save("Without file again", 200, Map.of(), null, null, null, "READY", null));

        storage.deleteRequestFile(first.id(), file.fileId());

        CaseVersionView gone = studio.caseVersions(first.id()).get(1);
        assertEquals("With file", gone.document().get("testName"));
        assertTrue(gone.missingFiles());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> restore(away, gone.id(), null));
        assertTrue(refused.getMessage().contains("no longer stored"), refused.getMessage());
        assertEquals("Without file again", studio.get(first.id(), environmentId).testName());
    }

    @Test
    void aNeverPublishedCaseHasNoVersionsAndAnUnknownVersionIsNotFound() {
        TestCaseView draft = studio.create(save("Draft", 200, Map.of(), null, null, null, "DRAFT", null));
        assertTrue(studio.caseVersions(draft.id()).isEmpty());
        assertThrows(NoSuchElementException.class, () -> restore(draft, "no-such-version", null));
    }

    private TestCaseView restore(TestCaseView current, String versionId, String expectedDraft) {
        return studio.restoreCaseVersion(current.id(), versionId,
                new RestoreCaseVersionRequest(current.updatedAt() == null ? null : current.updatedAt().toString(),
                        expectedDraft, environmentId));
    }

    /** Far enough apart that versions sort by when they were published. */
    private TestCaseView publish(TestCaseView current, SaveTestDataRequest request) throws InterruptedException {
        Thread.sleep(5);
        return studio.update(current.id(), request);
    }

    private SaveTestDataRequest save(String name, int status, Map<String, Object> body, String responseMatch,
                                     String ignoreFields, RunAuth auth, String lifecycle, String enabled) {
        return new SaveTestDataRequest(specId, planId, "ok", name, "{{baseUrl}}", Map.of(), Map.of(), Map.of(), Map.of(),
                new HashMap<>(body), status, "JSON", null, responseMatch, ignoreFields, auth, enabled, lifecycle, null,
                null, null, 5000L, "application/json", environmentId, null, null, null, null, null);
    }
}
