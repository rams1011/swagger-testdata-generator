package com.tx.api.test.generator.studio.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TESTDATA_SAFETY_ENABLED binds the way the app binds it at start-up: from the system environment, which Spring can
 * read as testdata.safety.enabled or testdata.safety-enabled. A getter such as isSafetyEnabled() on StudioProperties
 * made it pick the second, with no setter, and the app failed to start.
 */
class SafetyConfigurationBindingTest {

    @Test
    void theEnvironmentVariableTurnsProductionSafetyOn() {
        StudioProperties properties = bind(Map.of("TESTDATA_SAFETY_ENABLED", "true", "TESTDATA_EDITION", "saas"));
        assertTrue(properties.getSafety().isEnabled());
        assertTrue(properties.safetyOn());
    }

    @Test
    void itIsOffUnlessSetAndNeedsLogin() {
        assertFalse(bind(Map.of("TESTDATA_EDITION", "saas")).safetyOn());
        assertFalse(bind(Map.of("TESTDATA_SAFETY_ENABLED", "true", "TESTDATA_EDITION", "local")).safetyOn());
    }

    private static StudioProperties bind(Map<String, Object> environment) {
        StandardEnvironment spring = new StandardEnvironment();
        MutablePropertySources sources = spring.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
        // application.yml maps the variables explicitly too; keep the same lines here.
        sources.addLast(new MapPropertySource("application", Map.of(
                "testdata.safety.enabled", "${TESTDATA_SAFETY_ENABLED:false}",
                "testdata.edition", "${TESTDATA_EDITION:datacenter}")));
        ConfigurationPropertySources.attach(spring);
        return Binder.get(spring).bind("testdata", StudioProperties.class).orElseGet(StudioProperties::new);
    }
}
