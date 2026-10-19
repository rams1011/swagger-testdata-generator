package com.tx.api.test.generator.studio;

import com.tx.api.test.generator.studio.config.H2Database;
import com.tx.api.test.generator.studio.config.HostingProfileValidator;
import com.tx.api.test.generator.studio.config.StudioDatasourceSettings;
import com.tx.api.test.generator.studio.config.StudioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
@EnableConfigurationProperties(StudioProperties.class)
public class TestdataStudioApplication {

    private static final Logger log = LoggerFactory.getLogger(TestdataStudioApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TestdataStudioApplication.class, args);
    }

    @Bean
    ApplicationRunner startupBanner(
            StudioProperties properties,
            StudioDatasourceSettings datasourceSettings,
            Environment environment,
            HostingProfileValidator hostingProfile) {
        return args -> {
            String port = environment.getProperty("local.server.port",
                    environment.getProperty("server.port", "8083"));
            log.info("Test Data API is running at http://localhost:{} (hosting={}, storage={})",
                    port, hostingProfile.hostingProfile().wireValue(), datasourceSettings.storageType());
            if (datasourceSettings.isH2()) {
                log.info("H2 JDBC URL: {}", datasourceSettings.jdbcUrl());
                log.info("File H2 is opened with AUTO_SERVER=TRUE so api-automation can read the same database while Studio is running");
                if (properties.getH2().isConsole()) {
                    log.info("H2 console: http://localhost:{}/h2-console", port);
                    log.info("H2 console login: driver={} user={} password=<empty unless testdata.h2.password is set>",
                            H2Database.DRIVER, properties.getH2().getUsername());
                    log.info("Do not Test Connection with jdbc:h2:~/test; that creates C:/Users/.../test and is not this app's database");
                }
            }
        };
    }
}
