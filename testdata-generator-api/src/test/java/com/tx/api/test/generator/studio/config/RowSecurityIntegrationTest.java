package com.tx.api.test.generator.studio.config;

import com.tx.api.test.generator.studio.TestSchema;
import com.tx.api.test.generator.studio.tenant.DatabaseTenant;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import com.tx.api.test.generator.studio.tenant.TenantDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Row-level security on a real PostgreSQL: SQL that forgets the tenant check still sees, changes and adds only its own
 * workspace's rows. Uses Docker (CI integration job), or an empty database named by TESTDATA_RLS_JDBC_URL whose user
 * TESTDATA_RLS_JDBC_USER owns it and is neither a superuser nor BYPASSRLS.
 */
class RowSecurityIntegrationTest {

    private static final String ACME = TenantContext.LOCAL_TENANT_ID;
    private static final String GLOBEX = TenantContext.PLATFORM_TENANT_ID;

    @AfterEach
    void signedOut() {
        TenantContext.clear();
        TenantContext.setSaasEnabled(false);
    }

    @Test
    void sqlThatForgetsTheTenantSeesOnlyItsOwn() throws Exception {
        String url = System.getenv("TESTDATA_RLS_JDBC_URL");
        if (url != null && !url.isBlank()) {
            check(url, System.getenv("TESTDATA_RLS_JDBC_USER"), "");
            return;
        }
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            assertNotNull(RowSecurityConfiguration.problem(true, admin), "a superuser skips row-level security");
            admin.execute("CREATE ROLE studio_app LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'studio'");
            admin.execute("CREATE DATABASE studio OWNER studio_app");
            check(postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/studio"), "studio_app", "studio");
        }
    }

    private static void check(String url, String user, String password) throws Exception {
        DriverManagerDataSource plain = new DriverManagerDataSource(url, user, password);
        migrateWithDataFromBefore(plain);
        try (Connection connection = plain.getConnection()) {
            SchemaSnapshotTest.assertMatchesSnapshot("PostgreSQL", SchemaSnapshot.describe(connection));
        }
        TenantContext.setSaasEnabled(true);
        // One shared connection, so each step also shows that the previous workspace doesn't linger on it.
        SingleConnectionDataSource shared = new SingleConnectionDataSource(url, user, password, true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(new TenantDataSource(shared));
            assertNull(RowSecurityConfiguration.problem(true, jdbc));
            for (String tenant : List.of(ACME, GLOBEX)) {
                TenantContext.runAs(tenant, () -> insert(jdbc, tenant));
            }
            assertEquals(0, count(jdbc), "no workspace, no rows");
            TenantContext.runAs(ACME, () -> {
                assertEquals(List.of(ACME), jdbc.queryForList("SELECT tenant_id FROM td_spec", String.class));
                assertEquals(1, jdbc.update("UPDATE td_test_plan SET description = 'changed'"));
                assertEquals(0, jdbc.update("DELETE FROM td_spec WHERE spec_id = ?", GLOBEX));
                assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO td_test_plan"
                        + " (plan_id, spec_id, tenant_id, name) VALUES ('x', ?, ?, 'Theirs')", GLOBEX, GLOBEX));
                assertEquals(2, DatabaseTenant.acrossTenants(() -> count(jdbc)), "on purpose only");
                assertEquals(1, count(jdbc));
            });
            TenantContext.runAs(GLOBEX, () -> {
                assertEquals(1, count(jdbc));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM td_test_plan WHERE description = 'changed'",
                        Integer.class));
                assertEquals(1, jdbc.update("DELETE FROM td_spec"), "and its plan with it");
            });
            assertEquals(0, count(jdbc));
            assertEquals(1, DatabaseTenant.acrossTenants(() -> count(jdbc)));
        } finally {
            shared.destroy();
        }
        JdbcTemplate off = new JdbcTemplate(new TenantDataSource(plain, false));
        assertEquals(1, count(off), "the off switch");
        assertEquals(0, count(new JdbcTemplate(plain)), "a connection that never names a workspace");
    }

    /** V11 and V12 on a database that already has collections, as in production. */
    private static void migrateWithDataFromBefore(DriverManagerDataSource plain) {
        Flyway.configure().dataSource(plain).locations("classpath:db/migration").target("10").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(plain);
        jdbc.update("INSERT INTO td_organization (org_id, slug, name) VALUES ('o', 'o', 'O')");
        jdbc.update("INSERT INTO td_tenant (tenant_id, slug, name, org_id) VALUES ('old', 'old', 'Old', 'o')");
        jdbc.update("INSERT INTO td_spec (spec_id, tenant_id, name, label, title) VALUES ('s', 'old', 'S', 'G', 'S')");
        jdbc.update("INSERT INTO td_test_plan (plan_id, spec_id, name) VALUES ('p', 's', 'Default')");
        jdbc.update("INSERT INTO td_environment (environment_id, spec_id, name, document_json) VALUES ('e', 's', 'E', '{}')");
        jdbc.update("INSERT INTO td_test_case (test_case_id, spec_id, plan_id, operation_key, test_name, http_method,"
                + " raw_path, endpoint, status_code, lifecycle) VALUES ('c', 's', 'p', 'ok', 'C', 'GET', '/', '/', 200, 'READY')");
        jdbc.update("INSERT INTO td_test_case_param (test_case_id, param_in, param_name) VALUES ('c', 'query', 'q')");
        jdbc.update("INSERT INTO td_test_case_environment (test_case_id, environment_id) VALUES ('c', 'e')");
        jdbc.update("INSERT INTO td_run (run_id, spec_id, run_trigger, passed, failed, skipped, started_at)"
                + " VALUES ('r', 's', 'case', 1, 0, 0, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO td_run_result (result_id, run_id, spec_id, run_trigger, outcome, created_at)"
                + " VALUES ('rr', 'r', 's', 'case', 'passed', CURRENT_TIMESTAMP)");

        TestSchema.migrate(plain);

        assertEquals(0, count(jdbc));
        JdbcTemplate every = new JdbcTemplate(new TenantDataSource(plain, false));
        for (String table : List.of("td_test_plan", "td_environment", "td_test_case", "td_test_case_param",
                "td_test_case_environment", "td_run", "td_run_result")) {
            assertEquals(List.of("old"), every.queryForList("SELECT tenant_id FROM " + table, String.class), table);
        }
        assertEquals(1, every.update("DELETE FROM td_spec"));
        assertEquals(0, every.queryForObject("SELECT COUNT(*) FROM td_run_result", Integer.class), "cascades still work");
    }

    private static void insert(JdbcTemplate jdbc, String tenant) {
        jdbc.update("INSERT INTO td_spec (spec_id, tenant_id, name, label, title) VALUES (?, ?, 'Spec', 'General', 'Spec')",
                tenant, tenant);
        jdbc.update("INSERT INTO td_test_plan (plan_id, spec_id, tenant_id, name) VALUES (?, ?, ?, 'Default')",
                tenant, tenant, tenant);
    }

    private static int count(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM td_spec", Integer.class);
    }
}
