package com.tx.api.test.generator.studio.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * PostgreSQL/H2 migrations use {@code db/migration}; MySQL uses {@code db/migration/mysql}
 * (different SQL dialect for the same version numbers).
 */
@SuppressWarnings("removal")
public class FlywayMySqlLocationsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String jdbcUrl = environment.getProperty("spring.datasource.url");
        if (jdbcUrl == null || !isMySqlFamily(jdbcUrl)) {
            return;
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.flyway.locations", "classpath:db/migration/mysql");
        environment.getPropertySources().addFirst(new MapPropertySource("studioFlywayMySql", properties));
    }

    private static boolean isMySqlFamily(String jdbcUrl) {
        String lower = jdbcUrl.toLowerCase();
        return lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
