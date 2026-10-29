package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.config.StudioProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecLocationGuardTest {

    private final OutboundUrlPolicy policy = new OutboundUrlPolicy(new StudioProperties());

    @Test
    void filePathIsRejectedWithoutThePath() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SpecLocationGuard.check("C:\\Windows\\win.ini", policy));
        assertTrue(exception.getMessage().contains("http or https"));
        assertFalse(exception.getMessage().toLowerCase().contains("windows"));
        assertNull(SpecLocationGuard.publicLocation("C:\\Windows\\win.ini"));
    }

    @Test
    void privateUrlIsRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SpecLocationGuard.check("http://127.0.0.1/openapi.json", policy));
        assertTrue(exception.getMessage().contains("not allowed"));
    }

    @Test
    void publicUrlCanBeStored() {
        assertEquals("https://example.com/openapi.json",
                SpecLocationGuard.publicLocation(" https://example.com/openapi.json "));
    }
}
