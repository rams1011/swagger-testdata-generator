package com.tx.api.test.generator.studio.config;

import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class StorageConfiguration {

    @Bean
    StudioStorage studioStorage(JdbcTemplate jdbcTemplate) {
        return new JdbcStudioStorage(jdbcTemplate);
    }
}
