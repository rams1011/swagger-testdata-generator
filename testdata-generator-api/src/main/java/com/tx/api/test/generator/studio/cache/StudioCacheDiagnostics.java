package com.tx.api.test.generator.studio.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime Redis cache status for startup logs and {@code GET /api/testdata/config}.
 */
@Component
public class StudioCacheDiagnostics {

    public enum Status {
        /** {@code testdata.cache.redis.enabled=false} — JDBC only, no per-read cache logs. */
        DISABLED,
        /** Redis probed successfully; {@link StudioRedisCache} wraps reads. */
        ACTIVE,
        /** Enabled in config but startup probe failed; JDBC fallback. */
        UNAVAILABLE
    }

    private final AtomicReference<Status> status = new AtomicReference<>(Status.DISABLED);
    private final AtomicReference<String> detail = new AtomicReference<>("Redis cache is disabled in configuration.");

    public Status status() {
        return status.get();
    }

    public String detail() {
        return detail.get();
    }

    public void markDisabled() {
        status.set(Status.DISABLED);
        detail.set("Redis cache disabled (TESTDATA_REDIS_CACHE_ENABLED=false). All reads use JDBC.");
    }

    public void markActive(String host, int port, boolean ssl) {
        status.set(Status.ACTIVE);
        detail.set("Redis cache active at " + host + ":" + port + (ssl ? " (TLS)" : "")
                + ". Watch logs for Studio cache HIT/MISS (Redis vs DB).");
    }

    public void markUnavailable(String host, int port, Throwable error) {
        status.set(Status.UNAVAILABLE);
        detail.set("Redis cache enabled but unavailable at " + host + ":" + port + ": "
                + StudioRedisFailureMessages.summarize(error)
                + ". All reads use JDBC until Redis is fixed and the API is restarted.");
    }

    public void noteRuntimeReadFailure(String operation, Throwable error) {
        detail.set("Redis read failed during " + operation + ": "
                + StudioRedisFailureMessages.summarize(error)
                + ". Request fell back to JDBC.");
    }
}
