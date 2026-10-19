package com.tx.api.test.generator.studio.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasourceConfigurationValidatorTest {

    @Test
    void acceptsSpringDatasourceUrl() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:h2:mem:test");
        DatasourceConfigurationValidator validator = new DatasourceConfigurationValidator(environment);
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void rejectsMissingUrl() {
        MockEnvironment environment = new MockEnvironment();
        DatasourceConfigurationValidator validator = new DatasourceConfigurationValidator(environment);
        assertThrows(IllegalStateException.class, validator::validate);
    }
}
