package com.tx.api.test.generator.studio.config;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CorsOriginPatternsTest {

    @Test
    void rewritesStarPortAndPairsLoopbackHosts() {
        StudioProperties.Saas saas = new StudioProperties.Saas();
        saas.setCorsOrigins("https://*.run.app,http://localhost:*,https://localhost:*");

        assertEquals(
                Arrays.asList(
                        "https://*.run.app",
                        "http://localhost:[*]",
                        "http://127.0.0.1:[*]",
                        "https://localhost:[*]",
                        "https://127.0.0.1:[*]"),
                Arrays.asList(saas.originPatterns()));
    }

    @Test
    void leavesExactOriginsAlone() {
        StudioProperties.Saas saas = new StudioProperties.Saas();
        saas.setCorsOrigins("https://studio.example.com");

        assertEquals(Arrays.asList("https://studio.example.com"), Arrays.asList(saas.originPatterns()));
    }

    @Test
    void doesNotAliasLocalhostInsideALongerHost() {
        StudioProperties.Saas saas = new StudioProperties.Saas();
        saas.setCorsOrigins("https://localhost.example.com");

        assertEquals(Arrays.asList("https://localhost.example.com"), Arrays.asList(saas.originPatterns()));
        assertFalse(Arrays.asList(saas.originPatterns()).contains("https://127.0.0.1.example.com"));
    }

    @Test
    void defaultAllowsHttpLoopbackOnAnyPort() {
        assertEquals(
                Arrays.asList("http://localhost:[*]", "http://127.0.0.1:[*]"),
                Arrays.asList(new StudioProperties.Saas().originPatterns()));
    }
}
