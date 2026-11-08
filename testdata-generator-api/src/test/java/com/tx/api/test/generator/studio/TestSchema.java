package com.tx.api.test.generator.studio;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/** Builds a test database with the production migrations, so tests and production share one schema. */
public final class TestSchema {

    private TestSchema() {
    }

    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
