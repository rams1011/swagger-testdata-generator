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
        assertDoesNotThrow(() -> OptimisticConcurrency.ensureCurrent(3, null, false));
        assertDoesNotThrow(() -> OptimisticConcurrency.ensureCurrent(3, 1L, true));
    }

    @Test
    void rejectsAnOlderRevision() {
        ConflictException conflict = assertThrows(ConflictException.class, () ->
                OptimisticConcurrency.ensureCurrent(3, 2L, false));
        assertTrue(conflict.getMessage().contains("newer version"));
    }

    @Test
    void acceptsTheCurrentRevision() {
        assertDoesNotThrow(() -> OptimisticConcurrency.ensureCurrent(3, 3L, false));
    }

    @Test
    void sameComparesAtMillisecondPrecision() {
        assertTrue(OptimisticConcurrency.same(
                Instant.parse("2024-01-01T00:00:00.123456789Z"), Instant.parse("2024-01-01T00:00:00.123Z")));
    }
}
