package com.tx.api.test.generator.studio.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.function.Supplier;

public class StudioRedisCache {

    public static final String NS_SPEC = "spec";
    public static final String NS_PLAN = "plan";
    public static final String NS_ENV = "env";
    public static final String NS_CASE = "case";
    public static final String NS_RUN = "run";
    public static final String NS_AUDIT = "audit";
    public static final String NS_FILE = "file";

    private static final Logger log = LoggerFactory.getLogger(StudioRedisCache.class);
    private static final String MDC_READ_SOURCE = "studioReadSource";

    private static final ObjectMapper CACHE_JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final StudioCacheDiagnostics diagnostics;

    public StudioRedisCache(StringRedisTemplate redis, StudioProperties properties,
                            StudioCacheDiagnostics diagnostics) {
        this.redis = redis;
        this.diagnostics = diagnostics;
        long ttlSeconds = Math.max(1L, properties.getCache().getRedis().getTtlSeconds());
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public <T> T readThrough(String namespace, String operation, String keySuffix, TypeReference<T> type,
                             Supplier<T> loadFromDb) {
        return readThrough(namespace, null, operation, keySuffix, type, loadFromDb);
    }

    public <T> T readThrough(String namespace, String scope, String operation, String keySuffix,
                             TypeReference<T> type, Supplier<T> loadFromDb) {
        String tenantId = TenantContext.tenantId();
        long generation = currentGeneration(tenantId, namespace, scope);
        String cacheKey = entryKey(tenantId, namespace, scope, generation, keySuffix);
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                logHit(operation, tenantId, cacheKey);
                return CACHE_JSON.readValue(cached, type);
            }
        } catch (Exception error) {
            diagnostics.noteRuntimeReadFailure(operation, error);
            log.warn("Studio cache MISS source=DB operation={} tenant={} key={} redisError={}",
                    operation, tenantId, cacheKey, StudioRedisFailureMessages.summarize(error));
        }
        T loaded = loadFromDb.get();
        logMiss(operation, tenantId, cacheKey);
        try {
            redis.opsForValue().set(cacheKey, CACHE_JSON.writeValueAsString(loaded), ttl);
        } catch (Exception error) {
            log.warn("Studio cache populate failed operation={} tenant={} key={} redisError={}",
                    operation, tenantId, cacheKey, StudioRedisFailureMessages.summarize(error));
        }
        return loaded;
    }

    public void invalidate(String namespace) {
        bumpGeneration(TenantContext.tenantId(), namespace, null);
    }

    public void invalidate(String namespace, String scope) {
        bumpGeneration(TenantContext.tenantId(), namespace, scope);
    }

    public void invalidateSpecCatalog() {
        invalidate(NS_SPEC);
    }

    /** Plans, environments, cases, and runs for one spec share scoped cache generations. */
    public void invalidateSpecWorkspace(String specId) {
        if (StringUtils.isBlank(specId)) {
            return;
        }
        invalidate(NS_PLAN, specId);
        invalidate(NS_ENV, specId);
        invalidate(NS_CASE, specId);
        invalidate(NS_RUN, specId);
    }

    private void logHit(String operation, String tenantId, String cacheKey) {
        MDC.put(MDC_READ_SOURCE, "redis");
        try {
            log.info("Studio cache HIT source=Redis operation={} tenant={} key={}", operation, tenantId, cacheKey);
        } finally {
            MDC.remove(MDC_READ_SOURCE);
        }
    }

    private void logMiss(String operation, String tenantId, String cacheKey) {
        MDC.put(MDC_READ_SOURCE, "db");
        try {
            log.info("Studio cache MISS source=DB operation={} tenant={} key={}", operation, tenantId, cacheKey);
        } finally {
            MDC.remove(MDC_READ_SOURCE);
        }
    }

    private void bumpGeneration(String tenantId, String namespace, String scope) {
        String generationKey = generationKey(tenantId, namespace, scope);
        try {
            redis.opsForValue().increment(generationKey);
            log.info("Studio cache invalidated tenant={} namespace={} scope={} key={}",
                    tenantId, namespace, scope == null ? "*" : scope, generationKey);
        } catch (Exception error) {
            log.warn("Studio cache invalidate failed tenant={} namespace={} scope={} key={} redisError={}",
                    tenantId, namespace, scope == null ? "*" : scope, generationKey,
                    StudioRedisFailureMessages.summarize(error));
        }
    }

    private long currentGeneration(String tenantId, String namespace, String scope) {
        try {
            String raw = redis.opsForValue().get(generationKey(tenantId, namespace, scope));
            if (raw == null || raw.isBlank()) {
                return 0L;
            }
            return Long.parseLong(raw);
        } catch (Exception error) {
            log.warn("Studio cache generation read failed tenant={} namespace={} scope={} redisError={}",
                    tenantId, namespace, scope == null ? "*" : scope, StudioRedisFailureMessages.summarize(error));
            return 0L;
        }
    }

    private static String generationKey(String tenantId, String namespace, String scope) {
        if (StringUtils.isBlank(scope)) {
            return "studio:cache:" + tenantId + ":" + namespace + ":gen";
        }
        return "studio:cache:" + tenantId + ":" + namespace + ":" + scope + ":gen";
    }

    private static String entryKey(String tenantId, String namespace, String scope, long generation, String suffix) {
        String scopePart = StringUtils.isBlank(scope) ? "" : scope + ":";
        return "studio:cache:" + tenantId + ":" + namespace + ":" + scopePart + generation + ":" + suffix;
    }
}
