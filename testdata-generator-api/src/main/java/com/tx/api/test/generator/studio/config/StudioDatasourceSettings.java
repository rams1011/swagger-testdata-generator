package com.tx.api.test.generator.studio.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StudioDatasourceSettings {

    private final String jdbcUrl;

    public StudioDatasourceSettings(@Value("${spring.datasource.url:}") String jdbcUrl) {
        this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public boolean isH2() {
        return jdbcUrl.startsWith("jdbc:h2:");
    }

    public String storageType() {
        return isH2() ? "h2" : "database";
    }
}
