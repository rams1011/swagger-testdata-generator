package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.dto.CollectionExport;
import com.tx.api.test.generator.studio.dto.CollectionImportResult;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
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
        try (Connection connection = dataSource.getConnection();
             Reader schema = new InputStreamReader(
                     new ClassPathResource("ddl/schema.sql").getInputStream(), StandardCharsets.UTF_8)) {
            RunScript.execute(connection, schema);
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("ALTER TABLE td_test_case_param DROP CONSTRAINT IF EXISTS ck_td_test_case_param_in");
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
        assertEquals("http://localhost:8080/employee-service",
                storage.findTestCase("case-create-role").orElseThrow().getBaseUrl());
    }

    @Test
    void importResolvesSharedBaseUrlAgainstTheTargetSpec() throws Exception {
        StoredSpec spec = localSpec();
        spec.setBaseUrl("https://int.example.com/employee-service");
        storage.saveSpec(spec);

        CollectionImporter.importInto(storage, goldenBytes(), "employee-service.Default.collection.json", "spec-local");

        assertEquals("https://int.example.com/employee-service",
                storage.findTestCase("case-create-role").orElseThrow().getBaseUrl());
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
                                create.expectedResponse(), create.enabled(), create.lifecycle(), create.testGroup(),
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
    void rejectsImportWhenTheSpecIsNotLoaded() throws Exception {
        CollectionFormatException error = assertThrows(CollectionFormatException.class,
                () -> CollectionImporter.importInto(storage, goldenBytes(), "collection.json", null));
        assertTrue(error.getMessage().contains("Load spec 'employee-service'"));
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
