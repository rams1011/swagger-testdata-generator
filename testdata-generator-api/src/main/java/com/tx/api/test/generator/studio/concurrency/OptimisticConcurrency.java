package com.tx.api.test.generator.studio.concurrency;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class OptimisticConcurrency {

    private OptimisticConcurrency() {
    }

    public static boolean force(Boolean forceOverwrite) {
        return Boolean.TRUE.equals(forceOverwrite);
    }

    public static boolean checkRequired(String expectedUpdatedAt, Boolean forceOverwrite) {
        return !force(forceOverwrite) && expectedUpdatedAt != null && !expectedUpdatedAt.isBlank();
    }

    public static Instant parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return toStorageInstant(Instant.parse(raw.trim()));
    }

    /** JDBC timestamps are millisecond-precision; keep reads/writes aligned for optimistic locking. */
    public static Instant toStorageInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }

    public static boolean same(Instant left, Instant right) {
        if (left == null || right == null) {
            return left == right;
        }
        return toStorageInstant(left).toEpochMilli() == toStorageInstant(right).toEpochMilli();
    }

    public static void ensureCurrent(Instant current, String expectedRaw, Boolean forceOverwrite) {
        if (!checkRequired(expectedRaw, forceOverwrite)) {
            return;
        }
        if (!same(current, parse(expectedRaw))) {
            throw new com.tx.api.test.generator.studio.api.ConflictException(
                    "Someone else saved a newer version. Reload their copy or overwrite.");
        }
    }
}
