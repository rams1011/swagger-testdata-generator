package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.ManualOperationRequest;
import com.tx.api.test.generator.studio.dto.SpecSummary;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import com.tx.api.test.generator.studio.tenant.TenantContext;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecCatalogServiceManualTest {

    private DataSource dataSource;
    private SpecCatalogService catalog;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("manual_rest_" + System.nanoTime()),
                "sa",
                "");
        h2.setDriverClassName(H2Database.DRIVER);
        dataSource = h2;
        try (Connection connection = dataSource.getConnection();
             Reader schema = new InputStreamReader(
                     new ClassPathResource("ddl/schema.sql").getInputStream(), StandardCharsets.UTF_8)) {
            RunScript.execute(connection, schema);
        }
        catalog = new SpecCatalogService(new JdbcStudioStorage(new JdbcTemplate(dataSource)));
        TenantContext.setSaasEnabled(false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (dataSource != null) {
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        }
    }

    @Test
    void createsEmptyRestCatalogAndAddsOperations() {
        SpecSummary created = catalog.load(new LoadSpecRequest(null, null, null, "http://localhost:9000",
                "HRM REST", "QA", true));
        assertTrue(created.manual());
        assertEquals(0, created.operationCount());
        assertEquals("http://localhost:9000", created.baseUrl());
        assertTrue(catalog.listOperations(created.specId()).isEmpty());

        OperationPage added = catalog.addOperation(created.specId(), new ManualOperationRequest(
                "POST",
                "users",
                "Create user",
                "Users",
                null,
                null,
                true,
                "{\"name\":\"Ada\"}",
                null,
                null,
                null
        ));
        assertEquals("POST", added.method());
        assertEquals("/users", added.path());
        assertEquals("Create user", added.summary());
        assertEquals(1, catalog.listOperations(created.specId()).size());
        assertTrue(catalog.getSummary(created.specId()).manual());

        catalog.deleteOperation(created.specId(), added.key());
        assertTrue(catalog.listOperations(created.specId()).isEmpty());
        assertEquals(0, catalog.getSummary(created.specId()).operationCount());
    }

    @Test
    void addsExtraOperationsOnSwaggerSpecs() {
        SpecSummary swagger = catalog.load(new LoadSpecRequest(null, swaggerSpec(), "ping.json",
                "http://localhost:8080", "Ping", null, null));
        assertFalse(swagger.manual());
        assertEquals(1, swagger.operationCount());

        OperationPage extra = catalog.addOperation(swagger.specId(), new ManualOperationRequest(
                "GET", "/extra", "Prod only ping", null, null, null, null, null, null, null, List.of("prod")));
        assertEquals("GET", extra.method());
        assertEquals("/extra", extra.path());
        assertEquals(List.of("prod"), extra.environmentNames());
        assertEquals(2, catalog.listOperations(swagger.specId()).size());

        SpecSummary manual = catalog.createManual(new LoadSpecRequest(null, null, null, null,
                "Manual", null, true));
        IllegalArgumentException reload = assertThrows(IllegalArgumentException.class, () ->
                catalog.reload(manual.specId(), new LoadSpecRequest(null, swaggerSpec(), null, null, null, null, null)));
        assertTrue(reload.getMessage().contains("not backed by a Swagger"));
    }

    private static String swaggerSpec() {
        return """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Ping", "version": "1.0.0" },
                  "paths": {
                    "/ping": {
                      "get": {
                        "responses": { "200": { "description": "ok" } }
                      }
                    }
                  }
                }
                """;
    }
}
