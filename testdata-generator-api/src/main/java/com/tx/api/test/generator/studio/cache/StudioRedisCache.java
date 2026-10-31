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

    private static final ObjectMapper CACHE_JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public StudioRedisCache(StringRedisTemplate redis, StudioProperties properties) {
        this.redis = redis;
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
                log.info("Studio read source=Redis operation={} tenant={} key={}", operation, tenantId, cacheKey);
                return CACHE_JSON.readValue(cached, type);
            }
        } catch (Exception error) {
            log.warn("Studio Redis read failed operation={} tenant={} key={}: {}",
                    operation, tenantId, cacheKey, error.toString());
        }
        T loaded = loadFromDb.get();
        log.info("Studio read source=DB operation={} tenant={} key={}", operation, tenantId, cacheKey);
        try {
            redis.opsForValue().set(cacheKey, CACHE_JSON.writeValueAsString(loaded), ttl);
        } catch (Exception error) {
            log.warn("Studio Redis write failed operation={} tenant={} key={}: {}",
                    operation, tenantId, cacheKey, error.toString());
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

    private void bumpGeneration(String tenantId, String namespace, String scope) {
        String generationKey = generationKey(tenantId, namespace, scope);
        redis.opsForValue().increment(generationKey);
        log.info("Studio cache invalidated tenant={} namespace={} scope={} key={}",
                tenantId, namespace, scope == null ? "*" : scope, generationKey);
    }

    private long currentGeneration(String tenantId, String namespace, String scope) {
        String raw = redis.opsForValue().get(generationKey(tenantId, namespace, scope));
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
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
