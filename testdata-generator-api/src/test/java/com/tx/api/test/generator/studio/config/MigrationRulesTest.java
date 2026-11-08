package com.tx.api.test.generator.studio.config;

import com.tx.api.test.generator.studio.TestSchema;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Rules the database itself enforces, checked on a database whose migrating connection has closed (each
 * DriverManagerDataSource connection is new), and the V3 move of environment names into links.
 */
class MigrationRulesTest {

    private static final String LOCAL_TENANT = "00000000-0000-0000-0000-000000000001";

    private DriverManagerDataSource h2;
    private JdbcTemplate jdbc;

    @BeforeEach
    void newDatabase() {
        h2 = new DriverManagerDataSource(H2Database.memoryJdbcUrl("rules_" + System.nanoTime()), "sa", "");
        h2.setDriverClassName(H2Database.DRIVER);
        jdbc = new JdbcTemplate(h2);
    }

    @AfterEach
    void closeDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void checksWorkAfterTheMigratingConnectionHasClosed() {
        TestSchema.migrate(h2);
        insertSpecPlanAndCase("case-1", "READY");

        jdbc.update("INSERT INTO td_test_case_param (test_case_id, param_in, param_name, param_value) VALUES (?, ?, ?, ?)",
                "case-1", "query", "page", "1");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO td_test_case_param (test_case_id, param_in, param_name, param_value) VALUES (?, ?, ?, ?)",
                "case-1", "body", "x", "1"));
        assertThrows(DataIntegrityViolationException.class, () -> insertCase("case-2", "plan-1", "PUBLISHED"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO td_membership (tenant_id, account_id, role) VALUES (?, ?, ?)", LOCAL_TENANT, "nobody", "GUEST"));
    }

    @Test
    void aCaseNeedsAPlanThatExists() {
        TestSchema.migrate(h2);
        insertSpecPlanAndCase("case-1", "READY");

        assertThrows(DataIntegrityViolationException.class, () -> insertCase("case-2", null, "READY"));
        assertThrows(DataIntegrityViolationException.class, () -> insertCase("case-3", "no-such-plan", "READY"));

        jdbc.update("DELETE FROM td_test_plan WHERE plan_id = ?", "plan-1");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM td_test_case", Integer.class), "a plan takes its cases");
    }

    @Test
    void versionThreeMovesEnvironmentNamesIntoLinks() {
        Flyway.configure().dataSource(h2).locations("classpath:db/migration").target("2").load().migrate();
        insertSpecPlanAndCase("case-1", "READY");
        jdbc.update("ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS environment_names TEXT");
        jdbc.update("INSERT INTO td_environment (environment_id, spec_id, name, document_json) VALUES (?, ?, ?, ?)",
                "env-staging", "spec-1", "Staging", "{}");
        jdbc.update("UPDATE td_test_case SET environment_names = ? WHERE test_case_id = ?",
                "[\"staging\", \"Missing\", \"Staging\"]", "case-1");

        TestSchema.migrate(h2);

        List<String> linked = jdbc.queryForList("""
                SELECT e.name FROM td_test_case_environment l
                  JOIN td_environment e ON e.environment_id = l.environment_id
                 WHERE l.test_case_id = 'case-1' ORDER BY e.name
                """, String.class);
        assertEquals(List.of("Missing", "Staging"), linked, "matched regardless of case; a missing one is created");
        assertFalse(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = 'td_test_case'", String.class).contains("environment_names"));
    }

    private void insertSpecPlanAndCase(String caseId, String lifecycle) {
        jdbc.update("INSERT INTO td_spec (spec_id, tenant_id, name, label, title) VALUES (?, ?, ?, ?, ?)",
                "spec-1", LOCAL_TENANT, "Spec", "General", "Spec");
        jdbc.update("INSERT INTO td_test_plan (plan_id, spec_id, name) VALUES (?, ?, ?)", "plan-1", "spec-1", "Default");
        insertCase(caseId, "plan-1", lifecycle);
    }

    private void insertCase(String caseId, String planId, String lifecycle) {
        jdbc.update("""
                INSERT INTO td_test_case (test_case_id, spec_id, plan_id, operation_key, test_name, http_method,
                                          raw_path, endpoint, status_code, lifecycle)
                VALUES (?, 'spec-1', ?, 'ok', 'Case', 'GET', '/ok', '/ok', 200, ?)
                """, caseId, planId, lifecycle);
    }
}
