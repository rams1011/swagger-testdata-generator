package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.dto.CollectionExport;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionExportTest {

    private DataSource dataSource;
    private JdbcStudioStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("collection_phase1_" + System.nanoTime()),
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
    void downloadsPrettyJsonWithCreateBeforeGet() throws Exception {
        CollectionMapper.writeTo(storage, CollectionDocuments.parseCollection(readGolden()));

        CollectionExport exported = CollectionExporter.export(storage, "spec-employee-service", "plan-default");

        assertEquals("employee-service.Default.collection.json", exported.fileName());
        assertEquals(CollectionFormat.JSON_MEDIA_TYPE, exported.mediaType());
        assertEquals(2, exported.caseCount());
        assertEquals(0, exported.fileCount());
        StudioCollection parsed = CollectionDocuments.parseCollection(
                new String(exported.content(), StandardCharsets.UTF_8));
        assertEquals("case-create-role", parsed.cases().get(0).id());
        assertEquals("case-get-role", parsed.cases().get(1).id());
        assertEquals("roleId=$.roleId", parsed.cases().get(0).extracts());
        assertEquals("{{baseUrl}}", parsed.cases().get(0).baseUrl());
        assertEquals(CollectionDocuments.parseTree(readGolden()),
                CollectionDocuments.parseTree(new String(exported.content(), StandardCharsets.UTF_8)));
    }

    @Test
    void zipsUploadsBesideTheCollectionJson() throws Exception {
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

        CollectionExport exported = CollectionExporter.export(storage, "spec-employee-service", "plan-default");

        assertEquals("employee-service.Default.collection.zip", exported.fileName());
        assertEquals(CollectionFormat.ZIP_MEDIA_TYPE, exported.mediaType());
        assertEquals(1, exported.fileCount());

        Map<String, byte[]> entries = unzip(exported.content());
        assertTrue(entries.containsKey("employee-service/Default.collection.json"));
        String json = new String(entries.get("employee-service/Default.collection.json"), StandardCharsets.UTF_8);
        StudioCollection parsed = CollectionDocuments.parseCollection(json);
        assertEquals("case-create-role", parsed.cases().get(0).id());
        assertEquals(1, parsed.cases().get(0).files().size());
        StudioCollectionFile file = parsed.cases().get(0).files().get(0);
        assertEquals("file-photo", file.fileId());
        assertEquals("photo.png", file.fileName());
        assertEquals("case-create-role/file-photo-photo.png", file.path());
        String zipPath = "employee-service/files/" + file.path();
        assertArrayEquals("png-bytes".getBytes(StandardCharsets.UTF_8), entries.get(zipPath));
    }

    @Test
    void leavesDisabledCasesOutOfTheCollection() throws Exception {
        CollectionMapper.writeTo(storage, CollectionDocuments.parseCollection(readGolden()));
        var disabled = storage.findTestCase("case-get-role").orElseThrow();
        disabled.setEnabled("N");
        storage.saveTestCase(disabled);

        CollectionExport exported = CollectionExporter.export(storage, "spec-employee-service", "plan-default");

        StudioCollection parsed = CollectionDocuments.parseCollection(
                new String(exported.content(), StandardCharsets.UTF_8));
        assertEquals(1, parsed.cases().size());
        assertEquals("case-create-role", parsed.cases().get(0).id());
    }

    @Test
    void rejectsAnEmptyPlan() throws Exception {
        CollectionMapper.writeTo(storage, CollectionDocuments.parseCollection(readGolden()));
        storage.deleteTestCase("case-create-role");
        storage.deleteTestCase("case-get-role");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CollectionExporter.export(storage, "spec-employee-service", "plan-default"));
        assertTrue(error.getMessage().contains("No test cases"));
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream stream = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = stream.getNextEntry()) != null) {
                entries.put(entry.getName(), stream.readAllBytes());
            }
        }
        return entries;
    }

    private static String readGolden() throws Exception {
        try (var stream = CollectionExportTest.class
                .getResourceAsStream("/collection/employee-service.Default.collection.json")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
