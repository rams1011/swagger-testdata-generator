package com.tx.api.test.generator.studio.audit;

import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.dto.AuditEventView;
import com.tx.api.test.generator.studio.model.StudioTestCase;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioAuditServiceTest {

    private DataSource dataSource;
    private StudioAuditService auditService;
    private JdbcStudioStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("audit_" + System.nanoTime()),
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
        auditService = new StudioAuditService(storage);
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
    void recordsCaseCreateAndUpdateEvents() {
        TenantContext.runAs(TenantContext.LOCAL_TENANT_ID, "acct-1", "qa@test", "OWNER", () -> {
            storage.saveSpec(spec("spec-1"));
            StudioTestCase testCase = caseRow("case-1", "spec-1");
            auditService.recordCaseCreated(testCase);
            testCase.setTestName("Renamed");
            auditService.recordCaseUpdated(testCase);

            java.util.List<AuditEventView> events = auditService.listForCase("case-1", 10);
            assertEquals(2, events.size());
            assertEquals("UPDATED", events.get(0).action());
            assertEquals("CREATED", events.get(1).action());
            assertEquals("qa@test", events.get(0).actorLabel());
            assertFalse(events.get(0).detail().isBlank());
        });
    }

    @Test
    void recordsSignInAndPublishOutsideASpec() {
        TenantContext.runAs("tenant-9", "acct-9", "qa@test", "OWNER", () -> {
            auditService.recordLogin("tenant-9", "acct-9", "qa@test");
            auditService.recordLoginFailed("tenant-9", "nobody@test");
            auditService.recordPublished(caseRow("case-9", "spec-9"));
            auditService.recordExported("spec-9", "Collection", "demo.json", 2);

            java.util.List<AuditEventView> events = auditService.listForWorkspace(10);
            assertEquals(4, events.size());
            java.util.Map<String, AuditEventView> byAction = new java.util.HashMap<>();
            for (AuditEventView event : events) {
                byAction.put(event.action(), event);
            }
            assertEquals("qa@test", byAction.get("LOGGED_IN").actorLabel());
            assertEquals("Sign-in failed", byAction.get("LOGIN_FAILED").detail());
            assertEquals("nobody@test", byAction.get("LOGIN_FAILED").actorLabel());
            assertEquals("PUBLISHED", byAction.get("PUBLISHED").action());
            assertTrue(byAction.get("EXPORTED").detail().contains("demo.json"));
        });
    }

    private static com.tx.api.test.generator.studio.model.StoredSpec spec(String id) {
        com.tx.api.test.generator.studio.model.StoredSpec spec =
                new com.tx.api.test.generator.studio.model.StoredSpec();
        spec.setSpecId(id);
        spec.setName("demo");
        spec.setTitle("Demo");
        spec.setOperationCount(0);
        return spec;
    }

    private static StudioTestCase caseRow(String id, String specId) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setId(id);
        testCase.setSpecId(specId);
        testCase.setOperationKey("GET /ping");
        testCase.setTestName("Ping");
        testCase.setMethod("GET");
        testCase.setRawPath("/ping");
        testCase.setEndpoint("/ping");
        testCase.setStatusCode(200);
        testCase.setEnabled("Y");
        return testCase;
    }
}
