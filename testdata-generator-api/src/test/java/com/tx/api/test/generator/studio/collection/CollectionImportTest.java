package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.TestSchema;
import java.nio.charset.StandardCharsets;
import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.dto.CollectionExport;
import com.tx.api.test.generator.studio.dto.CollectionImportResult;
import com.tx.api.test.generator.studio.model.CaseLifecycle;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionImportTest {

    private DataSource dataSource;
    private JdbcStudioStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("collection_phase2_" + System.nanoTime()),
                "sa",
                "");
        h2.setDriverClassName(H2Database.DRIVER);
        dataSource = h2;
        TestSchema.migrate(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        storage = new JdbcStudioStorage(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        }
    }

    @Test
    void clonesAPlanOntoASpecLoadedUnderADifferentId() throws Exception {
        storage.saveSpec(localSpec());

        CollectionImportResult imported = CollectionImporter.importInto(
                storage, goldenBytes(), "employee-service.Default.collection.json", null);

        assertEquals("spec-local", imported.specId());
        assertEquals("plan-default", imported.planId());
        assertTrue(imported.planCreated());
        assertEquals(2, imported.createdCases());
        assertEquals(0, imported.updatedCases());
        assertEquals("case-create-role", storage.listTestCases("spec-local", "plan-default", null).get(0).getId());
        assertEquals("{{roleId}}", storage.findTestCase("case-get-role").orElseThrow().getPathParams().get("id"));
        assertEquals("roleId=$.roleId", storage.findTestCase("case-create-role").orElseThrow().getExtracts());
        assertEquals("{{baseUrl}}",
                storage.findTestCase("case-create-role").orElseThrow().getBaseUrl());
    }

    @Test
    void importStoresSharedBaseUrlAsThePlaceholder() throws Exception {
        StoredSpec spec = localSpec();
        spec.setBaseUrl("https://int.example.com/employee-service");
        storage.saveSpec(spec);

        CollectionImporter.importInto(storage, goldenBytes(), "employee-service.Default.collection.json", "spec-local");

        assertEquals("{{baseUrl}}",
                storage.findTestCase("case-create-role").orElseThrow().getBaseUrl(),
                "a case on the collection's base URL follows the environment");
        assertEquals("{{baseUrl}}", CollectionMapper.readFrom(storage, "spec-local", "plan-default")
                .cases().get(0).baseUrl());
    }

    @Test
    void upsertsByCaseIdAndCreatesAMissingPlan() throws Exception {
        storage.saveSpec(localSpec());
        StudioTestPlan smoke = new StudioTestPlan();
        smoke.setId("plan-smoke");
        smoke.setSpecId("spec-local");
        smoke.setName("Smoke");
        storage.savePlan(smoke);

        CollectionImporter.importInto(storage, goldenBytes(), "collection.json", "spec-local");
        StudioCollection edited = CollectionDocuments.parseCollection(readGolden());
        StudioCollectionCase create = edited.cases().get(0);
        StudioCollection updatedDocument = new StudioCollection(
                edited.format(),
                edited.version(),
                edited.spec(),
                edited.plan(),
                java.util.List.of(
                        new StudioCollectionCase(
                                create.id(), create.operationKey(), create.testName(), create.method(), create.path(),
                                create.endpoint(), create.baseUrl(), create.contentType(), create.pathParams(),
                                create.queryParams(), create.headers(), create.cookies(), Map.of("code", "UPDATED"),
                                create.responseJsonSchema(), create.statusCode(), create.responseType(),
                                create.expectedResponse(), "ignore_order", " $.createdAt \n\n$..updatedAt\n", null, create.enabled(), create.lifecycle(), create.testGroup(),
                                create.jsonPathAssertions(), "roleId=$.id", create.maxResponseTimeMs(), create.files(),
                                create.environments()),
                        edited.cases().get(1)
                )
        );

        CollectionImportResult imported = CollectionImporter.importInto(
                storage, updatedDocument, Map.of(), "spec-local");

        assertFalse(imported.planCreated());
        assertEquals(0, imported.createdCases());
        assertEquals(2, imported.updatedCases());
        assertEquals("UPDATED", ((Map<?, ?>) storage.findTestCase("case-create-role").orElseThrow()
                .getRequestBody()).get("code"));
        assertEquals("roleId=$.id", storage.findTestCase("case-create-role").orElseThrow().getExtracts());
        assertEquals("ignoreOrder", storage.findTestCase("case-create-role").orElseThrow().getResponseMatch());
        assertEquals("$.createdAt\n$..updatedAt", storage.findTestCase("case-create-role").orElseThrow().getIgnoreFields());
        assertEquals(2, storage.listPlans("spec-local").size());
    }

    @Test
    void importsZipUploadsOntoTheMatchedSpec() throws Exception {
        CollectionMapper.writeTo(storage, CollectionDocuments.parseCollection(readGolden()));
        StudioRequestFile photo = new StudioRequestFile();
        photo.setFileId("file-photo");
        photo.setTestCaseId("case-create-role");
        photo.setFileName("photo.png");
        photo.setContentType("image/png");
        photo.setContent("png-bytes".getBytes(StandardCharsets.UTF_8));
        photo.setSizeBytes(9);
        photo.setCreatedAt(Instant.now());
        storage.saveRequestFile(photo);
        CollectionExport zip = CollectionExporter.export(storage, "spec-employee-service", "plan-default");
        storage.deleteSpec("spec-employee-service");
        storage.saveSpec(localSpec());

        CollectionImportResult imported = CollectionImporter.importInto(
                storage, zip.content(), zip.fileName(), null);

        assertEquals(1, imported.importedFiles());
        assertArrayEquals("png-bytes".getBytes(StandardCharsets.UTF_8),
                storage.findRequestFile("case-create-role", "file-photo").orElseThrow().getContent());
    }

    @Test
    void memberImportKeepsPublishedCasesAsDrafts() throws Exception {
        storage.saveSpec(localSpec());

        CollectionImporter.importInto(storage, goldenBytes(), "collection.json", "spec-local", null, false);

        assertEquals(CaseLifecycle.DRAFT, storage.findTestCase("case-create-role").orElseThrow().getLifecycle());
        assertEquals(CaseLifecycle.DRAFT, storage.findTestCase("case-get-role").orElseThrow().getLifecycle());
    }

    @Test
    void rejectsImportWhenTheSpecIsNotLoaded() throws Exception {
        CollectionFormatException error = assertThrows(CollectionFormatException.class,
                () -> CollectionImporter.importInto(storage, goldenBytes(), "collection.json", null));
        assertTrue(error.getMessage().contains("Load spec 'employee-service'"));
    }

    @Test
    void collectionVariablesTravelWithTheCollectionAndSecretsStayPlaceholders() throws Exception {
        storage.saveSpec(localSpec());
        CollectionImporter.importInto(storage, goldenBytes(), "collection.json", "spec-local");
        storage.saveCollectionVariables("spec-local", StudioVariables.toDocument(java.util.List.of(
                new StudioVariable("tenant", "acme", false), new StudioVariable("apiKey", null, true))));

        StudioCollection exported = CollectionMapper.readFrom(storage, "spec-local", "plan-default");
        assertEquals(java.util.List.of(new StudioVariable("tenant", "acme", false),
                new StudioVariable("apiKey", "${STUDIO_VAR_APIKEY}", true)), exported.variables());

        storage.saveCollectionVariables("spec-local", StudioVariables.toDocument(java.util.List.of(
                new StudioVariable("tenant", "local", false), new StudioVariable("kept", "yes", false))));
        CollectionImporter.importInto(storage, exported, Map.of(), "spec-local");

        assertEquals(java.util.List.of(new StudioVariable("tenant", "acme", false),
                        new StudioVariable("kept", "yes", false), new StudioVariable("apiKey", null, true)),
                StudioVariables.fromDocument(storage.findCollectionVariables("spec-local").orElseThrow()),
                "the file's rows replace the same names, others stay, and no secret value is stored");
    }

    @Test
    void dataRowsTravelWithTheirCase() throws Exception {
        storage.saveSpec(localSpec());
        CollectionImporter.importInto(storage, goldenBytes(), "collection.json", "spec-local");
        var stored = storage.findTestCase("case-create-role").orElseThrow();
        stored.setDataRows(com.tx.api.test.generator.studio.service.DataRows.normalize(
                "[{\"$name\":\"Admin\",\"role\":\"ADMIN\",\"limit\":5}]"));
        storage.saveTestCase(stored);

        StudioCollection exported = CollectionMapper.readFrom(storage, "spec-local", "plan-default");
        String json = CollectionDocuments.toJson(exported);
        assertTrue(json.contains("\"limit\": 5"), "numbers stay numbers in the file");
        storage.findTestCase("case-create-role").ifPresent(testCase -> {
            testCase.setDataRows(null);
            storage.saveTestCase(testCase);
        });
        CollectionImporter.importInto(storage, CollectionDocuments.parseCollection(json), Map.of(), "spec-local");

        assertEquals("[{\"$name\":\"Admin\",\"role\":\"ADMIN\",\"limit\":5}]",
                storage.findTestCase("case-create-role").orElseThrow().getDataRows());
    }

    @Test
    void importRefusesARawSecretCollectionVariableBeforeWritingAnything() throws Exception {
        storage.saveSpec(localSpec());
        StudioCollection golden = CollectionDocuments.parseCollection(readGolden());
        StudioCollection leaking = new StudioCollection(golden.format(), golden.version(), golden.spec(),
                golden.plan(), golden.cases(), java.util.List.of(new StudioVariable("apiKey", "real-key", true)));

        CollectionFormatException refused = assertThrows(CollectionFormatException.class,
                () -> CollectionImporter.importInto(storage, leaking, Map.of(), "spec-local"));
        assertTrue(refused.getMessage().contains("apiKey"), refused.getMessage());
        assertTrue(storage.listTestCases("spec-local", null, null).isEmpty());
    }

    private static StoredSpec localSpec() {
        StoredSpec spec = new StoredSpec();
        spec.setSpecId("spec-local");
        spec.setName("employee-service");
        spec.setTitle("Employee Service");
        spec.setLabel("General");
        return spec;
    }

    private static byte[] goldenBytes() throws Exception {
        return readGolden().getBytes(StandardCharsets.UTF_8);
    }

    private static String readGolden() throws Exception {
        try (var stream = CollectionImportTest.class
                .getResourceAsStream("/collection/employee-service.Default.collection.json")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
