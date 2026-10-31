package com.tx.api.test.generator.studio.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tx.api.test.generator.studio.audit.AuditAction;
import com.tx.api.test.generator.studio.audit.AuditResourceType;
import com.tx.api.test.generator.studio.cache.StudioRedisCache;
import com.tx.api.test.generator.studio.dto.AuditEventView;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioRun;
import com.tx.api.test.generator.studio.model.StudioRunResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RedisCachingStudioStorage extends ForwardingStudioStorage {

    private static final TypeReference<List<StoredSpec>> SPEC_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<StoredSpec> SPEC_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Long> LONG_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<StudioTestPlan>> PLAN_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<StudioTestPlan> PLAN_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<StoredEnvironment>> ENV_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<StoredEnvironment> ENV_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<StudioTestCase>> CASE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<StudioTestCase> CASE_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<StudioRequestFile>> FILE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<StudioRun>> RUN_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<StudioRun> RUN_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<StudioRunResult> RUN_RESULT_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<AuditEventView>> AUDIT_LIST_TYPE = new TypeReference<>() {
    };

    private final StudioRedisCache cache;

    public RedisCachingStudioStorage(StudioStorage delegate, StudioRedisCache cache) {
        super(delegate);
        this.cache = cache;
    }

    // --- Spec catalog (GET /api/specs) ---

    @Override
    public void saveSpec(StoredSpec spec) {
        super.saveSpec(spec);
        cache.invalidateSpecCatalog();
        if (spec != null && StringUtils.isNotBlank(spec.getSpecId())) {
            cache.invalidateSpecWorkspace(spec.getSpecId());
        }
    }

    @Override
    public void deleteSpec(String specId) {
        super.deleteSpec(specId);
        cache.invalidateSpecCatalog();
        cache.invalidateSpecWorkspace(specId);
        cache.invalidate(StudioRedisCache.NS_AUDIT);
    }

    @Override
    public Optional<StoredSpec> findSpec(String specId) {
        StoredSpec cached = cache.readThrough(
                StudioRedisCache.NS_SPEC,
                "findSpec",
                "spec:" + specId,
                SPEC_TYPE,
                () -> super.findSpec(specId).orElse(null));
        return Optional.ofNullable(cached);
    }

    @Override
    public List<StoredSpec> listSpecs() {
        return cache.readThrough(
                StudioRedisCache.NS_SPEC,
                "listSpecs",
                "spec-list:all",
                SPEC_LIST_TYPE,
                super::listSpecs);
    }

    /** Distinct labels must come from the database, not cached spec list pages. */
    @Override
    public List<String> listDistinctSpecLabels() {
        return delegate().listDistinctSpecLabels();
    }

    @Override
    public List<StoredSpec> listSpecs(SpecListFilter filter) {
        String suffix = "spec-list:" + SpecListFilterCacheKeys.forFilter(filter);
        return cache.readThrough(
                StudioRedisCache.NS_SPEC,
                "listSpecs",
                suffix,
                SPEC_LIST_TYPE,
                () -> super.listSpecs(filter));
    }

    @Override
    public long countSpecs(SpecListFilter filter) {
        String suffix = "spec-count:" + SpecListFilterCacheKeys.forFilter(filter);
        Long count = cache.readThrough(
                StudioRedisCache.NS_SPEC,
                "countSpecs",
                suffix,
                LONG_TYPE,
                () -> super.countSpecs(filter));
        return count == null ? 0L : count;
    }

    // --- Plans (GET /api/specs/{specId}/plans) ---

    @Override
    public void savePlan(StudioTestPlan plan) {
        super.savePlan(plan);
        if (plan != null && StringUtils.isNotBlank(plan.getSpecId())) {
            cache.invalidate(StudioRedisCache.NS_PLAN, plan.getSpecId());
            cache.invalidate(StudioRedisCache.NS_CASE, plan.getSpecId());
        }
        if (plan != null && StringUtils.isNotBlank(plan.getId())) {
            cache.invalidate(StudioRedisCache.NS_PLAN);
        }
    }

    @Override
    public void deletePlan(String planId) {
        Optional<StudioTestPlan> existing = super.findPlan(planId);
        super.deletePlan(planId);
        existing.ifPresent(plan -> {
            cache.invalidate(StudioRedisCache.NS_PLAN, plan.getSpecId());
            cache.invalidate(StudioRedisCache.NS_CASE, plan.getSpecId());
        });
        cache.invalidate(StudioRedisCache.NS_PLAN);
    }

    @Override
    public Optional<StudioTestPlan> findPlan(String planId) {
        StudioTestPlan cached = cache.readThrough(
                StudioRedisCache.NS_PLAN,
                "findPlan",
                StorageCacheKeys.planById(planId),
                PLAN_TYPE,
                () -> super.findPlan(planId).orElse(null));
        return Optional.ofNullable(cached);
    }

    @Override
    public List<StudioTestPlan> listPlans(String specId) {
        return cache.readThrough(
                StudioRedisCache.NS_PLAN,
                specId,
                "listPlans",
                StorageCacheKeys.planList(specId),
                PLAN_LIST_TYPE,
                () -> super.listPlans(specId));
    }

    // --- Environments (GET /api/specs/{specId}/environments) ---

    @Override
    public void saveEnvironment(StoredEnvironment environment) {
        super.saveEnvironment(environment);
        if (environment != null && StringUtils.isNotBlank(environment.getSpecId())) {
            cache.invalidate(StudioRedisCache.NS_ENV, environment.getSpecId());
        }
    }

    @Override
    public void deleteEnvironment(String environmentId) {
        Optional<StoredEnvironment> existing = super.findEnvironment(environmentId);
        super.deleteEnvironment(environmentId);
        existing.ifPresent(env -> cache.invalidate(StudioRedisCache.NS_ENV, env.getSpecId()));
    }

    @Override
    public List<StoredEnvironment> listEnvironments(String specId) {
        return cache.readThrough(
                StudioRedisCache.NS_ENV,
                specId,
                "listEnvironments",
                StorageCacheKeys.environmentList(specId),
                ENV_LIST_TYPE,
                () -> super.listEnvironments(specId));
    }

    @Override
    public Optional<StoredEnvironment> findEnvironment(String environmentId) {
        StoredEnvironment cached = cache.readThrough(
                StudioRedisCache.NS_ENV,
                "findEnvironment",
                StorageCacheKeys.environmentById(environmentId),
                ENV_TYPE,
                () -> super.findEnvironment(environmentId).orElse(null));
        return Optional.ofNullable(cached);
    }

    @Override
    public Optional<StoredEnvironment> findEnvironmentByName(String specId, String name) {
        StoredEnvironment cached = cache.readThrough(
                StudioRedisCache.NS_ENV,
                specId,
                "findEnvironmentByName",
                StorageCacheKeys.environmentByName(specId, name),
                ENV_TYPE,
                () -> super.findEnvironmentByName(specId, name).orElse(null));
        return Optional.ofNullable(cached);
    }

    // --- Test cases (GET /api/testdata, GET /api/testdata/{id}) ---

    @Override
    public void saveTestCase(StudioTestCase testCase) {
        super.saveTestCase(testCase);
        touchCaseCache(testCase == null ? null : testCase.getSpecId());
    }

    @Override
    public void updateResponseSchema(String testCaseId, Map<String, Object> schema) {
        Optional<StudioTestCase> existing = super.findTestCase(testCaseId);
        super.updateResponseSchema(testCaseId, schema);
        existing.ifPresent(item -> touchCaseCache(item.getSpecId()));
    }

    @Override
    public void deleteTestCase(String testCaseId) {
        Optional<StudioTestCase> existing = super.findTestCase(testCaseId);
        super.deleteTestCase(testCaseId);
        existing.ifPresent(item -> touchCaseCache(item.getSpecId()));
    }

    @Override
    public Optional<StudioTestCase> findTestCase(String testCaseId) {
        StudioTestCase cached = cache.readThrough(
                StudioRedisCache.NS_CASE,
                "findTestCase",
                StorageCacheKeys.caseById(testCaseId),
                CASE_TYPE,
                () -> super.findTestCase(testCaseId).orElse(null));
        return Optional.ofNullable(cached);
    }

    @Override
    public List<StudioTestCase> listTestCases(String specId, String planId, String operationKey) {
        return cache.readThrough(
                StudioRedisCache.NS_CASE,
                specId,
                "listTestCases",
                StorageCacheKeys.caseList(specId, planId, operationKey),
                CASE_LIST_TYPE,
                () -> super.listTestCases(specId, planId, operationKey));
    }

    // --- Request file metadata (GET list only; downloads stay uncached) ---

    @Override
    public void saveRequestFile(StudioRequestFile file) {
        super.saveRequestFile(file);
        if (file != null && StringUtils.isNotBlank(file.getTestCaseId())) {
            cache.invalidate(StudioRedisCache.NS_FILE, file.getTestCaseId());
        }
    }

    @Override
    public void deleteRequestFile(String testCaseId, String fileId) {
        super.deleteRequestFile(testCaseId, fileId);
        cache.invalidate(StudioRedisCache.NS_FILE, testCaseId);
    }

    @Override
    public void assignRequestFiles(String testCaseId, List<String> fileIds) {
        super.assignRequestFiles(testCaseId, fileIds);
        cache.invalidate(StudioRedisCache.NS_FILE, testCaseId);
    }

    @Override
    public void deleteRequestFilesForCase(String testCaseId) {
        super.deleteRequestFilesForCase(testCaseId);
        cache.invalidate(StudioRedisCache.NS_FILE, testCaseId);
    }

    @Override
    public List<StudioRequestFile> listRequestFiles(String testCaseId) {
        return cache.readThrough(
                StudioRedisCache.NS_FILE,
                testCaseId,
                "listRequestFiles",
                StorageCacheKeys.fileList(testCaseId),
                FILE_LIST_TYPE,
                () -> super.listRequestFiles(testCaseId));
    }

    // --- Run history (GET /api/runs) ---

    @Override
    public void saveRun(StudioRun run) {
        super.saveRun(run);
        if (run != null && StringUtils.isNotBlank(run.getSpecId())) {
            cache.invalidate(StudioRedisCache.NS_RUN, run.getSpecId());
        }
    }

    @Override
    public void deleteRun(String runId) {
        Optional<StudioRun> existing = super.findRun(runId);
        super.deleteRun(runId);
        existing.ifPresent(run -> cache.invalidate(StudioRedisCache.NS_RUN, run.getSpecId()));
    }

    @Override
    public int pruneOldestRuns(String specId, int keep) {
        int pruned = super.pruneOldestRuns(specId, keep);
        if (pruned > 0) {
            cache.invalidate(StudioRedisCache.NS_RUN, specId);
        }
        return pruned;
    }

    @Override
    public Optional<StudioRun> findRun(String runId) {
        StudioRun cached = cache.readThrough(
                StudioRedisCache.NS_RUN,
                "findRun",
                StorageCacheKeys.runById(runId),
                RUN_TYPE,
                () -> super.findRun(runId).orElse(null));
        return Optional.ofNullable(cached);
    }

    @Override
    public Optional<StudioRunResult> findRunResult(String runId, String resultId) {
        StudioRunResult cached = cache.readThrough(
                StudioRedisCache.NS_RUN,
                "findRunResult",
                StorageCacheKeys.runResult(runId, resultId),
                RUN_RESULT_TYPE,
                () -> super.findRunResult(runId, resultId).orElse(null));
        return Optional.ofNullable(cached);
    }

    @Override
    public List<StudioRun> listRuns(RunHistoryFilter filter) {
        String scope = filter == null ? null : filter.specId();
        return cache.readThrough(
                StudioRedisCache.NS_RUN,
                scope,
                "listRuns",
                StorageCacheKeys.runList(filter),
                RUN_LIST_TYPE,
                () -> super.listRuns(filter));
    }

    // --- Audit (GET /api/testdata/{id}/audit, /api/audit/...) ---

    @Override
    public void appendAuditEvent(String eventId, String specId, AuditResourceType resourceType, String resourceId,
                                 AuditAction action, String actorAccountId, String actorLabel, String detail,
                                 Instant occurredAt) {
        super.appendAuditEvent(eventId, specId, resourceType, resourceId, action, actorAccountId, actorLabel, detail,
                occurredAt);
        cache.invalidate(StudioRedisCache.NS_AUDIT);
    }

    @Override
    public List<AuditEventView> listAuditEvents(AuditResourceType resourceType, String resourceId, int limit) {
        return cache.readThrough(
                StudioRedisCache.NS_AUDIT,
                "listAuditEvents",
                StorageCacheKeys.auditByResource(resourceType, resourceId, limit),
                AUDIT_LIST_TYPE,
                () -> super.listAuditEvents(resourceType, resourceId, limit));
    }

    @Override
    public List<AuditEventView> listAuditEventsForSpec(String specId, int limit) {
        return cache.readThrough(
                StudioRedisCache.NS_AUDIT,
                specId,
                "listAuditEventsForSpec",
                StorageCacheKeys.auditForSpec(specId, limit),
                AUDIT_LIST_TYPE,
                () -> super.listAuditEventsForSpec(specId, limit));
    }

    @Override
    public void appendAuditEventForTenant(String tenantId, String eventId, String specId,
                                          AuditResourceType resourceType, String resourceId,
                                          AuditAction action, String actorAccountId, String actorLabel,
                                          String detail, Instant occurredAt) {
        super.appendAuditEventForTenant(tenantId, eventId, specId, resourceType, resourceId, action, actorAccountId,
                actorLabel, detail, occurredAt);
        cache.invalidate(StudioRedisCache.NS_AUDIT);
    }

    @Override
    public List<AuditEventView> listAuditEventsForTenant(int limit) {
        return cache.readThrough(
                StudioRedisCache.NS_AUDIT,
                "listAuditEventsForTenant",
                StorageCacheKeys.auditForTenant(limit),
                AUDIT_LIST_TYPE,
                () -> super.listAuditEventsForTenant(limit));
    }

    private void touchCaseCache(String specId) {
        if (StringUtils.isNotBlank(specId)) {
            cache.invalidate(StudioRedisCache.NS_CASE, specId);
        }
        cache.invalidate(StudioRedisCache.NS_CASE);
    }
}
