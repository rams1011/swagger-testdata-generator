package com.tx.api.test.generator.studio.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class H2Database {

    public static final String DRIVER = "org.h2.Driver";
    static final String DEFAULT_FILE = "./studio-output/testdata";
    static final String DEFAULT_USERNAME = "sa";
    static final String OPTIONS = "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;"
            + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    private H2Database() {
    }

    public static String jdbcUrl(String file, boolean inMemory) {
        if (inMemory) {
            return "jdbc:h2:mem:testdata_studio;" + OPTIONS;
        }
        Path path = Path.of(blankToDefault(file, DEFAULT_FILE)).toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot create H2 directory: " + parent, exception);
            }
        }
        return "jdbc:h2:file:" + path.toString().replace('\\', '/') + ";" + OPTIONS;
    }

    static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
