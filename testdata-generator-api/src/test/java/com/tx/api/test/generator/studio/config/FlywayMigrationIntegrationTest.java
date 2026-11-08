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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migrates real PostgreSQL and MySQL databases (CI integration job, needs Docker) and holds both to
 * schema-snapshot.txt, the file SchemaSnapshotTest holds H2 to. So all three databases get the same tables,
 * columns, indexes and foreign keys, and the same default workspaces.
 */
@Testcontainers(parallel = false)
class FlywayMigrationIntegrationTest {

    @Test
    void postgresMatchesTheSnapshot() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            check("PostgreSQL", postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                    "classpath:db/migration");
        }
    }

    @Test
    void mysqlMatchesTheSnapshot() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
            mysql.start();
            check("MySQL", mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                    "classpath:db/migration_mysql");
        }
    }

    private static void check(String database, String jdbcUrl, String username, String password, String location)
            throws Exception {
        Flyway.configure().dataSource(jdbcUrl, username, password).locations(location).load().migrate();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            SchemaSnapshotTest.assertMatchesSnapshot(database, SchemaSnapshot.describe(connection));
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT COUNT(*) FROM td_tenant t JOIN td_subscription s ON s.org_id = t.org_id"
                                 + " WHERE t.slug IN ('local', 'saas-platform') AND t.ready = TRUE")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1), database + ": the default workspaces are seeded");
            }
        }
        // Running again changes nothing: the seed file only inserts what's missing.
        Flyway.configure().dataSource(jdbcUrl, username, password).locations(location).load().migrate();
    }
}
