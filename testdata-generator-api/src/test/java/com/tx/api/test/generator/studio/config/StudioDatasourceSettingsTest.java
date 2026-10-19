package com.tx.api.test.generator.studio.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioDatasourceSettingsTest {

    @Test
    void detectsH2() {
        StudioDatasourceSettings settings = new StudioDatasourceSettings("jdbc:h2:file:./testdata");
        assertTrue(settings.isH2());
        assertEquals("h2", settings.storageType());
    }

    @Test
    void detectsExternalDatabase() {
        StudioDatasourceSettings settings = new StudioDatasourceSettings("jdbc:postgresql://db/test");
        assertFalse(settings.isH2());
        assertEquals("database", settings.storageType());
    }
}
