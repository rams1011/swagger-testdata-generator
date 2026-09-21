package com.tx.api.test.generator.studio.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class StorageEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String EXCEL_EXCLUDES = String.join(",",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration",
            "org.springframework.boot.autoconfigure.h2.H2ConsoleAutoConfiguration");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String type = normalize(environment.getProperty("testdata.storage.type", "excel"));
        Map<String, Object> values = new HashMap<>();
        values.put("testdata.storage.type", type);
        if ("h2".equals(type)) {
            applyH2(environment, values);
        } else if ("database".equals(type)) {
            values.put("testdata.storage.engine", "jdbc");
            values.put("spring.sql.init.mode", "always");
            values.put("spring.h2.console.enabled", false);
            String instance = firstNonBlank(
                    environment.getProperty("TESTDATA_ALLOYDB_INSTANCE"),
                    environment.getProperty("ALLOYDB_INSTANCE_NAME"));
            if (instance != null) {
                String url = environment.getProperty("spring.datasource.url", "");
                values.put("spring.datasource.url", alloyDbUrl(url, instance));
                values.put("spring.datasource.hikari.data-source-properties.ssl", "false");
                values.put("spring.datasource.hikari.data-source-properties.sslmode", "disable");
            }
        } else {
            values.put("testdata.storage.engine", "excel");
            values.put("spring.autoconfigure.exclude", EXCEL_EXCLUDES);
            values.put("spring.sql.init.mode", "never");
            values.put("spring.h2.console.enabled", false);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("testdataStorage", values));
    }

    private void applyH2(ConfigurableEnvironment environment, Map<String, Object> values) {
        boolean inMemory = Boolean.parseBoolean(environment.getProperty("testdata.h2.in-memory", "false"));
        boolean console = Boolean.parseBoolean(environment.getProperty("testdata.h2.console", "true"));
        String file = environment.getProperty("testdata.h2.file", H2Database.DEFAULT_FILE);
        String username = environment.getProperty("testdata.h2.username", H2Database.DEFAULT_USERNAME);
        String password = environment.getProperty("testdata.h2.password", "");

        values.put("testdata.storage.engine", "jdbc");
        values.put("spring.sql.init.mode", "always");
        values.put("spring.datasource.url", H2Database.jdbcUrl(file, inMemory));
        values.put("spring.datasource.username", username);
        values.put("spring.datasource.password", password);
        values.put("spring.datasource.driver-class-name", H2Database.DRIVER);
        values.put("spring.h2.console.enabled", console);
        values.put("spring.h2.console.path", "/h2-console");
        values.put("spring.h2.console.settings.web-allow-others", false);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    static String alloyDbUrl(String url, String instance) {
        return "jdbc:postgresql:///" + databaseName(url)
                + "?socketFactory=com.google.cloud.alloydb.SocketFactory"
                + "&alloydbInstanceName=" + instance
                + "&alloydbIpType=PUBLIC"
                + "&alloydbRefreshStrategy=lazy";
    }

    static String databaseName(String url) {
        if (url == null || url.isBlank()) {
            return "testdata_studio";
        }
        int scheme = url.indexOf("://");
        String rest = scheme >= 0 ? url.substring(scheme + 3) : url;
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return "testdata_studio";
        }
        String db = rest.substring(slash + 1);
        int query = db.indexOf('?');
        if (query >= 0) {
            db = db.substring(0, query);
        }
        return db.isBlank() ? "testdata_studio" : db;
    }

    private String normalize(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "excel";
        }
        String type = rawType.trim().toLowerCase(Locale.ROOT);
        if ("h2".equals(type) || "h2db".equals(type) || "h2database".equals(type)) {
            return "h2";
        }
        if ("db".equals(type) || "jdbc".equals(type) || "sql".equals(type)
                || "postgres".equals(type) || "postgresql".equals(type)) {
            return "database";
        }
        if ("xlsx".equals(type) || "file".equals(type)) {
            return "excel";
        }
        return type;
    }
}
