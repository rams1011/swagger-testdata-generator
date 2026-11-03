package com.tx.api.test.generator.studio.config;

import com.tx.api.test.generator.studio.cache.StudioCacheDiagnostics;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class StorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StorageConfiguration.class);

    @Bean
    JdbcStudioStorage jdbcStudioStorage(JdbcTemplate jdbcTemplate) {
        return new JdbcStudioStorage(jdbcTemplate);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "testdata.cache.redis",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    StudioStorage studioStorage(JdbcStudioStorage jdbcStudioStorage, StudioCacheDiagnostics diagnostics) {
        diagnostics.markDisabled();
        log.info(
                "Studio Redis cache DISABLED (testdata.cache.redis.enabled=false). "
                        + "All reads use JDBC; enable TESTDATA_REDIS_CACHE_ENABLED=true for Redis HIT/MISS logs.");
        return jdbcStudioStorage;
    }
}
