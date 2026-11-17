package com.tx.api.test.generator.studio.safety;

import com.tx.api.test.generator.studio.dto.EnvironmentSummary;
import com.tx.api.test.generator.studio.dto.ExportEnvironmentRequest;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.SaveEnvironmentRequest;
import com.tx.api.test.generator.studio.service.SpecCatalogService;
import com.tx.api.test.generator.studio.service.TestDataStudioService;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Environment protection is a column of its own: saving, importing and exporting an environment never move it. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=local",
                "TESTDATA_EDITION=local",
                "TESTDATA_H2_FILE=target/environment-protection-test/testdata",
                "TESTDATA_H2_JDBC_URL=jdbc:h2:mem:environment_protection;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1"
        })
class EnvironmentProtectionStorageTest {

    private static final String DEFINITION = """
            {"openapi":"3.0.3","info":{"title":"T","version":"1"},"paths":{"/ok":{"get":{"operationId":"ok",
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
    private EnvironmentSummary production;

    @BeforeEach
    void protectedEnvironment() {
        specId = catalog.load(new LoadSpecRequest(null, DEFINITION, null, "http://127.0.0.1:1",
                "Protection " + UUID.randomUUID(), null, null, null, null)).specId();
        production = studio.createEnvironment(specId,
                new SaveEnvironmentRequest("Production", "https://api.acme.example", "NONE"));
        assertFalse(production.protectedEnvironment(), "new environments start unprotected");
        storage.setEnvironmentProtected(production.id(), true);
    }

    @Test
    void editingTheEnvironmentKeepsItProtected() {
        EnvironmentSummary edited = studio.updateEnvironment(specId, production.id(),
                new SaveEnvironmentRequest("Production", "https://api2.acme.example", null));
        assertTrue(edited.protectedEnvironment());
        assertTrue(studio.listEnvironments(specId).stream()
                .filter(environment -> environment.id().equals(production.id()))
                .findFirst().orElseThrow().protectedEnvironment());
    }

    @Test
    void importingOverItKeepsItProtectedAndExportLeavesProtectionOut() {
        byte[] exported = studio.exportEnvironment(new ExportEnvironmentRequest(specId, production.id())).content();
        String file = new String(exported, StandardCharsets.UTF_8);
        assertFalse(file.contains("protected"), file);

        studio.importEnvironment(file.getBytes(StandardCharsets.UTF_8), specId, false);
        assertTrue(storage.findEnvironment(production.id()).orElseThrow().isProtectedEnvironment());
    }

    @Test
    void aDuplicateOfAProtectedEnvironmentIsProtected() {
        EnvironmentSummary copy = studio.duplicateEnvironment(specId, production.id());
        assertTrue(copy.protectedEnvironment());
        assertTrue(storage.findEnvironment(copy.id()).orElseThrow().isProtectedEnvironment());

        EnvironmentSummary staging = studio.createEnvironment(specId,
                new SaveEnvironmentRequest("Staging", "https://staging.acme.example", "NONE"));
        assertFalse(studio.duplicateEnvironment(specId, staging.id()).protectedEnvironment());
    }
}
