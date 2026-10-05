package com.tx.api.test.generator.studio.concurrency;

import com.tx.api.test.generator.studio.api.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimisticConcurrencyTest {

    @Test
    void skipsCheckWhenExpectedMissingOrForceOverwrite() {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        assertDoesNotThrow(() -> OptimisticConcurrency.ensureCurrent(now, null, false));
        assertDoesNotThrow(() -> OptimisticConcurrency.ensureCurrent(now, "", false));
        assertDoesNotThrow(() -> OptimisticConcurrency.ensureCurrent(now, "2024-01-02T00:00:00Z", true));
    }

    @Test
    void rejectsStaleRevision() {
        Instant current = Instant.parse("2024-01-02T00:00:00.001Z");
        ConflictException conflict = assertThrows(ConflictException.class, () ->
                OptimisticConcurrency.ensureCurrent(current, "2024-01-01T00:00:00Z", false));
        assertTrue(conflict.getMessage().contains("newer version"));
    }

    @Test
    void acceptsMatchingRevisionAtMillisecondPrecision() {
        Instant current = Instant.parse("2024-01-01T00:00:00.123Z");
        assertDoesNotThrow(() -> OptimisticConcurrency.ensureCurrent(current, "2024-01-01T00:00:00.123Z", false));
    }
}
