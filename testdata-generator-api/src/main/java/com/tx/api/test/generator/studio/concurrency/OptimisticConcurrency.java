package com.tx.api.test.generator.studio.concurrency;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class OptimisticConcurrency {

    private OptimisticConcurrency() {
    }

    public static boolean force(Boolean forceOverwrite) {
        return Boolean.TRUE.equals(forceOverwrite);
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

    /** True when a save must match {@code expectedRevision}: one was given and it isn't a forced overwrite. */
    public static boolean checkRequired(Long expectedRevision, Boolean forceOverwrite) {
        return !force(forceOverwrite) && expectedRevision != null;
    }

    /** Each save adds one to a row's revision, so a save based on an older revision is someone else's lost update. */
    public static void ensureCurrent(long currentRevision, Long expectedRevision, Boolean forceOverwrite) {
        if (checkRequired(expectedRevision, forceOverwrite) && currentRevision != expectedRevision) {
            throw new com.tx.api.test.generator.studio.api.ConflictException(
                    "Someone else saved a newer version. Reload their copy or overwrite.");
        }
    }
}
