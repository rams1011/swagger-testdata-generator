package com.tx.api.test.generator.studio.config;

import com.tx.api.test.generator.studio.storage.ExcelStudioStorage;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class StorageConfiguration {

    @Bean
    @ConditionalOnProperty(name = "testdata.storage.engine", havingValue = "excel", matchIfMissing = true)
    StudioStorage excelStudioStorage(StudioProperties properties) {
        return new ExcelStudioStorage(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "testdata.storage.engine", havingValue = "jdbc")
    StudioStorage jdbcStudioStorage(JdbcTemplate jdbcTemplate) {
        return new JdbcStudioStorage(jdbcTemplate);
    }
}
