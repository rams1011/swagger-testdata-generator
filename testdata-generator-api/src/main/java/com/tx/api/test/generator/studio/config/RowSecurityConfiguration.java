package com.tx.api.test.generator.studio.config;

import com.tx.api.test.generator.studio.tenant.TenantDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** PostgreSQL row-level security: connections carry their workspace, and startup says whether it is in force. */
@Configuration
public class RowSecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RowSecurityConfiguration.class);
    /** Tables migration V12 puts policies on. */
    private static final int TABLES = 15;
    private static final String ENABLED = "testdata.database.row-level-security";
    private static final String REQUIRED = "testdata.database.require-row-level-security";

    @Bean
    static BeanPostProcessor tenantDataSource(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource dataSource && !(bean instanceof TenantDataSource)
                        && postgres(environment)
                        ? new TenantDataSource(dataSource, environment.getProperty(ENABLED, Boolean.class, true))
                        : bean;
            }
        };
    }

    @Bean
    ApplicationRunner rowSecurityCheck(Environment environment, JdbcTemplate jdbcTemplate) {
        return arguments -> {
            if (postgres(environment)) {
                report(problem(environment.getProperty(ENABLED, Boolean.class, true), jdbcTemplate),
                        environment.getProperty(REQUIRED, Boolean.class, false));
            }
        };
    }

    /** Why row-level security doesn't protect this database; null when it does. */
    static String problem(boolean enabled, JdbcTemplate jdbcTemplate) {
        if (!enabled) {
            return ENABLED + " is false";
        }
        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))) {
            return "the database user is a superuser or has BYPASSRLS, so PostgreSQL skips it";
        }
        Integer forced = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = current_schema() AND c.relname LIKE 'td\\_%'
                   AND c.relrowsecurity AND c.relforcerowsecurity
                """, Integer.class);
        return forced == null || forced < TABLES ? "its policies are missing (migration V12)" : null;
    }

    static void report(String problem, boolean required) {
        if (problem == null) {
            log.info("PostgreSQL row-level security is in force: each connection sees only its workspace's rows");
            return;
        }
        if (required) {
            throw new IllegalStateException("PostgreSQL row-level security is not in force: " + problem
                    + " (docs/DATABASE.md, Row-level security)");
        }
        log.warn("PostgreSQL row-level security is not in force: {}. Workspaces are kept apart by the app's own checks "
                + "only (docs/DATABASE.md, Row-level security).", problem);
    }

    private static boolean postgres(Environment environment) {
        return environment.getProperty("spring.datasource.url", "").startsWith("jdbc:postgresql:");
    }
}
