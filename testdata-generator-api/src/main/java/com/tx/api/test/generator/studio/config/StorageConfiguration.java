package com.tx.api.test.generator.studio.config;

import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class StorageConfiguration {

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
    StudioStorage studioStorage(JdbcStudioStorage jdbcStudioStorage) {
        return jdbcStudioStorage;
    }
}
