package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.dto.EnvironmentExport;
import com.tx.api.test.generator.studio.dto.EnvironmentSummary;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentImportTest {

    private DataSource dataSource;
    private JdbcStudioStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("environment_phase3_" + System.nanoTime()),
                "sa",
                "");
        h2.setDriverClassName(H2Database.DRIVER);
        dataSource = h2;
        try (Connection connection = dataSource.getConnection();
             Reader schema = new InputStreamReader(
                     new ClassPathResource("ddl/schema.sql").getInputStream(), StandardCharsets.UTF_8)) {
            RunScript.execute(connection, schema);
        }
        storage = new JdbcStudioStorage(new JdbcTemplate(dataSource));
        storage.saveSpec(localSpec());
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        }
    }

    @Test
    void exportDownloadsStoredEnvironmentWithoutChangingAuth() {
        StudioEnvironment created = EnvironmentMapper.fromInputs(
                "local", "http://localhost:8080/employee-service", "BEARER");
        StoredEnvironment stored = EnvironmentExporter.upsert(storage, "spec-local", created);

        EnvironmentExport exported = EnvironmentExporter.export(localSpec(), stored);
        assertEquals("employee-service.local.environment.json", exported.fileName());
        StudioEnvironment environment = CollectionDocuments.parseEnvironment(
                new String(exported.content(), StandardCharsets.UTF_8));
        assertEquals("local", environment.name());
        assertEquals("http://localhost:8080/employee-service", environment.values().get("baseUrl"));
        assertEquals("${AUTOMATION_BEARER_TOKEN}", environment.auth().token());
        assertTrue(environment.caseOverlays().isEmpty());
        assertEquals(1, storage.listEnvironments("spec-local").size());
    }

    @Test
    void importUpsertsByNameAndRejectsSecrets() throws Exception {
        EnvironmentImporter.importInto(storage, "spec-local", readBytes("employee-service.local.environment.json"));
        EnvironmentImporter.importInto(storage, "spec-local", readBytes("employee-service.int.environment.json"));
        EnvironmentImporter.importInto(storage, "spec-local", readBytes("employee-service.int.environment.json"));

        assertEquals(2, storage.listEnvironments("spec-local").size());
        EnvironmentSummary local = EnvironmentMapper.toSummary(
                storage.findEnvironmentByName("spec-local", "local").orElseThrow());
        EnvironmentSummary integration = EnvironmentMapper.toSummary(
                storage.findEnvironmentByName("spec-local", "int").orElseThrow());
        assertEquals("http://localhost:8080/employee-service", local.baseUrl());
        assertEquals("BEARER", local.authType());
        assertEquals("https://int.example.com/employee-service", integration.baseUrl());

        CollectionFormatException error = assertThrows(CollectionFormatException.class, () -> {
            String secret = """
                    {"format":"testdata.studio.environment","version":1,"name":"bad","values":{"baseUrl":"http://x"},"auth":{"type":"BEARER","token":"super-secret-token"}}
                    """;
            EnvironmentImporter.importInto(storage, "spec-local", secret.getBytes(StandardCharsets.UTF_8));
        });
        assertTrue(error.getMessage().contains("placeholder"));
    }

    @Test
    void formSaveCreatesAndMergesWithoutLosingValues() {
        StudioEnvironment created = EnvironmentMapper.fromInputs("int", "https://int.example.com/api", "BEARER");
        EnvironmentExporter.upsert(storage, "spec-local", created);
        EnvironmentSummary summary = EnvironmentMapper.toSummary(
                storage.findEnvironmentByName("spec-local", "int").orElseThrow());
        assertEquals("https://int.example.com/api", summary.baseUrl());
        assertEquals("BEARER", summary.authType());

        StudioEnvironment merged = EnvironmentMapper.merge(created, "int", "https://int.example.com/v2", "BEARER");
        assertEquals("${AUTOMATION_BEARER_TOKEN}", merged.auth().token());
        assertEquals("https://int.example.com/v2", merged.values().get("baseUrl"));

        StudioEnvironment withOverlay = EnvironmentOverlay.withCase(created, "case-create-role",
                new StudioEnvironmentCaseOverlay(Map.of("code", "INT"), null, null, null, null, null, null, null));
        StudioEnvironment renamed = EnvironmentMapper.merge(withOverlay, "int", "https://int.example.com/v2", "BEARER");
        assertEquals("INT", ((Map<?, ?>) renamed.caseOverlays().get("case-create-role").body()).get("code"));

        assertEquals("local-copy", EnvironmentMapper.copyName("local", name -> false));
        assertEquals("local-copy-2", EnvironmentMapper.copyName("local", "local-copy"::equals));
    }

    @Test
    void variablesRoundTripWithoutStoringSecrets() {
        StudioEnvironment created = EnvironmentMapper.fromInputs("int", "https://int.example.com/api", "NONE",
                List.of(new StudioVariable("tenant", "acme", false),
                        new StudioVariable("apiKey", null, true)));
        StoredEnvironment stored = EnvironmentExporter.upsert(storage, "spec-local", created);
        EnvironmentSummary summary = EnvironmentMapper.toSummary(stored);
        assertEquals(List.of(new StudioVariable("tenant", "acme", false), new StudioVariable("apiKey", null, true)),
                summary.variables());

        StudioEnvironment kept = EnvironmentMapper.merge(created, "int", "https://int.example.com/v2", "NONE", null);
        assertEquals(2, kept.variableList().size());

        EnvironmentExport exported = EnvironmentExporter.export(localSpec(), stored);
        String json = new String(exported.content(), StandardCharsets.UTF_8);
        assertTrue(json.contains("${STUDIO_VAR_APIKEY}"));

        EnvironmentImporter.importInto(storage, "spec-local", json.replace("\"int\"", "\"int2\"")
                .getBytes(StandardCharsets.UTF_8));
        EnvironmentSummary imported = EnvironmentMapper.toSummary(
                storage.findEnvironmentByName("spec-local", "int2").orElseThrow());
        assertEquals(new StudioVariable("apiKey", null, true), imported.variables().get(1));

        assertThrows(CollectionFormatException.class, () -> EnvironmentMapper.fromInputs("bad", "http://x", "NONE",
                List.of(new StudioVariable("apiKey", "real-secret", true))));
        assertThrows(CollectionFormatException.class, () -> EnvironmentMapper.fromInputs("bad", "http://x", "NONE",
                List.of(new StudioVariable("1bad", "v", false))));
        assertThrows(CollectionFormatException.class, () -> EnvironmentMapper.fromInputs("bad", "http://x", "NONE",
                List.of(new StudioVariable("a", "1", false), new StudioVariable("a", "2", false))));
    }

    @Test
    void globalVariablesArePerTenantDocument() {
        assertTrue(storage.findGlobalVariables().isEmpty());
        storage.saveGlobalVariables("{\"variables\":[{\"key\":\"a\",\"value\":\"1\",\"secret\":false}]}");
        storage.saveGlobalVariables("{\"variables\":[{\"key\":\"b\",\"value\":\"2\",\"secret\":false}]}");
        assertTrue(storage.findGlobalVariables().orElseThrow().contains("\"b\""));
    }

    private static StoredSpec localSpec() {
        StoredSpec spec = new StoredSpec();
        spec.setSpecId("spec-local");
        spec.setName("employee-service");
        spec.setTitle("Employee Service");
        spec.setLabel("General");
        spec.setBaseUrl("http://localhost:8080/employee-service");
        return spec;
    }

    private static byte[] readBytes(String name) throws Exception {
        try (var stream = EnvironmentImportTest.class.getResourceAsStream("/collection/" + name)) {
            return stream.readAllBytes();
        }
    }

    @Test
    void exportIncludesWorkspaceGlobalsWithSecretsAsPlaceholders() {
        StudioEnvironment created = EnvironmentMapper.fromInputs("int", "http://int.example.com", "NONE");
        StoredEnvironment stored = EnvironmentExporter.upsert(storage, "spec-local", created);

        EnvironmentExport exported = EnvironmentExporter.export(localSpec(), stored, java.util.List.of(
                new StudioVariable("clientId", "web", false), new StudioVariable("apiKey", null, true)));
        String json = new String(exported.content(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"globals\""));
        assertTrue(json.contains("\"clientId\""));
        assertTrue(json.contains("${STUDIO_VAR_APIKEY}"));
        assertEquals("int", CollectionDocuments.parseEnvironment(json).name());
        assertFalse(new String(EnvironmentExporter.export(localSpec(), stored).content(), StandardCharsets.UTF_8)
                .contains("\"globals\""));
    }
}
