package com.tx.api.test.generator.studio.concurrency;

import java.time.Instant;

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
        return Instant.parse(raw.trim());
    }

    public static boolean same(Instant left, Instant right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.toEpochMilli() == right.toEpochMilli();
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
