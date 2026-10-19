package com.tx.api.test.generator.studio.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Flyway migrations against real database engines (CI integration job).
 */
@Testcontainers(parallel = false)
class FlywayMigrationIntegrationTest {

    @Test
    void postgresMigrationsApply() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            migrate(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            assertTableExists(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), "td_spec");
        }
    }

    @Test
    void mysqlMigrationsApply() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
            mysql.start();
            migrate(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            assertTableExists(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), "td_spec");
        }
    }

    private static void migrate(String jdbcUrl, String username, String password) {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        flyway.migrate();
    }

    private static void assertTableExists(String jdbcUrl, String username, String password, String table)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
        }
    }
}
