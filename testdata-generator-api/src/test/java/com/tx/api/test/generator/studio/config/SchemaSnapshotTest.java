package com.tx.api.test.generator.studio.config;

import com.tx.api.test.generator.studio.TestSchema;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The migrated schema must match src/test/resources/schema-snapshot.txt. FlywayMigrationIntegrationTest holds
 * PostgreSQL and MySQL to the same file, so the two migration folders can't drift apart.
 */
class SchemaSnapshotTest {

    static final Path SNAPSHOT = Path.of("src/test/resources/schema-snapshot.txt");

    @Test
    void migratedSchemaMatchesTheSnapshot() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                H2Database.memoryJdbcUrl("snapshot_" + System.nanoTime()), "sa", "");
        h2.setDriverClassName(H2Database.DRIVER);
        TestSchema.migrate(h2);
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            assertMatchesSnapshot("H2", SchemaSnapshot.describe(connection));
            statement.execute("SHUTDOWN");
        }
    }

    /** On a mismatch, writes what was found under target so it can be compared with the snapshot. */
    static void assertMatchesSnapshot(String database, String actual) throws Exception {
        String expected = Files.readString(SNAPSHOT, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!expected.equals(actual)) {
            Path found = Path.of("target", "schema-snapshot." + database.toLowerCase(Locale.ROOT) + ".txt");
            Files.createDirectories(found.getParent());
            Files.writeString(found, actual, StandardCharsets.UTF_8);
            assertEquals(expected, actual, database + " schema differs from " + SNAPSHOT + " (found: " + found
                    + "). For an intended change, add a migration to both folders and update the snapshot.");
        }
    }
}
