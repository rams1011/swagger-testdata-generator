package com.tx.api.test.generator.studio.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Configuration
@ConditionalOnProperty(name = "spring.h2.console.enabled", havingValue = "true")
class H2ConsoleConnectionSettings {

    private static final Logger log = LoggerFactory.getLogger(H2ConsoleConnectionSettings.class);

    @Bean
    ApplicationRunner h2ConsoleSavedConnection(StudioProperties properties) {
        return args -> {
            if (!properties.getH2().isConsole()) {
                return;
            }
            Path file = Path.of(System.getProperty("user.home"), ".h2.server.properties");
            Properties props = load(file);
            String jdbcUrl = properties.getH2().jdbcUrl();
            String username = H2Database.blankToDefault(properties.getH2().getUsername(), H2Database.DEFAULT_USERNAME);
            props.setProperty("0", "Testdata Studio|org.h2.Driver|" + jdbcUrl + "|" + username);
            props.setProperty("webAllowOthers", "false");
            store(file, props);
            log.info("H2 console saved connection updated. Use this JDBC URL (not jdbc:h2:~/test): {}", jdbcUrl);
        };
    }

    private Properties load(Path file) {
        Properties props = new Properties();
        if (!Files.exists(file)) {
            return props;
        }
        try (InputStream input = Files.newInputStream(file)) {
            props.load(input);
        } catch (IOException exception) {
            log.warn("Could not read {}", file, exception);
        }
        return props;
    }

    private void store(Path file, Properties props) {
        try (OutputStream output = Files.newOutputStream(file)) {
            props.store(output, "Updated by testdata-generator-api so the console defaults to the studio database");
        } catch (IOException exception) {
            log.warn("Could not write H2 console settings to {}", file, exception);
        }
    }
}
