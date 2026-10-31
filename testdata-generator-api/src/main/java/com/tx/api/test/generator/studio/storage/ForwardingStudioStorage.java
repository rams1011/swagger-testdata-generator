package com.tx.api.test.generator.studio.storage;

import com.tx.api.test.generator.studio.audit.AuditAction;
import com.tx.api.test.generator.studio.audit.AuditResourceType;
import com.tx.api.test.generator.studio.dto.AuditEventView;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioRun;
import com.tx.api.test.generator.studio.model.StudioRunResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class ForwardingStudioStorage implements StudioStorage {

    private final StudioStorage delegate;

    ForwardingStudioStorage(StudioStorage delegate) {
        this.delegate = delegate;
    }

    protected StudioStorage delegate() {
        return delegate;
    }

    @Override
    public void saveSpec(StoredSpec spec) {
        delegate.saveSpec(spec);
    }

    @Override
    public Optional<StoredSpec> findSpec(String specId) {
        return delegate.findSpec(specId);
    }

    @Override
    public List<StoredSpec> listSpecs() {
        return delegate.listSpecs();
    }

    @Override
    public List<String> listDistinctSpecLabels() {
        return delegate.listDistinctSpecLabels();
    }

    @Override
    public List<StoredSpec> listSpecs(SpecListFilter filter) {
        return delegate.listSpecs(filter);
    }

    @Override
    public long countSpecs(SpecListFilter filter) {
        return delegate.countSpecs(filter);
    }

    @Override
    public void deleteSpec(String specId) {
        delegate.deleteSpec(specId);
    }

    @Override
    public void ensureSpecNameUniqueIndex() {
        delegate.ensureSpecNameUniqueIndex();
    }

    @Override
    public void savePlan(StudioTestPlan plan) {
        delegate.savePlan(plan);
    }

    @Override
    public void deletePlan(String planId) {
        delegate.deletePlan(planId);
    }

    @Override
    public Optional<StudioTestPlan> findPlan(String planId) {
        return delegate.findPlan(planId);
    }

    @Override
    public List<StudioTestPlan> listPlans(String specId) {
        return delegate.listPlans(specId);
    }

    @Override
    public List<StoredEnvironment> listEnvironments(String specId) {
        return delegate.listEnvironments(specId);
    }

    @Override
    public Optional<StoredEnvironment> findEnvironment(String environmentId) {
        return delegate.findEnvironment(environmentId);
    }

    @Override
    public Optional<StoredEnvironment> findEnvironmentByName(String specId, String name) {
        return delegate.findEnvironmentByName(specId, name);
    }

    @Override
    public void saveEnvironment(StoredEnvironment environment) {
        delegate.saveEnvironment(environment);
    }

    @Override
    public void deleteEnvironment(String environmentId) {
        delegate.deleteEnvironment(environmentId);
    }

    @Override
    public void saveTestCase(StudioTestCase testCase) {
        delegate.saveTestCase(testCase);
    }

    @Override
    public void updateResponseSchema(String testCaseId, Map<String, Object> schema) {
        delegate.updateResponseSchema(testCaseId, schema);
    }

    @Override
    public void deleteTestCase(String testCaseId) {
        delegate.deleteTestCase(testCaseId);
    }

    @Override
    public Optional<StudioTestCase> findTestCase(String testCaseId) {
        return delegate.findTestCase(testCaseId);
    }

    @Override
    public List<StudioTestCase> listTestCases(String specId, String planId, String operationKey) {
        return delegate.listTestCases(specId, planId, operationKey);
    }

    @Override
    public void saveRequestFile(StudioRequestFile file) {
        delegate.saveRequestFile(file);
    }

    @Override
    public Optional<StudioRequestFile> findRequestFile(String testCaseId, String fileId) {
        return delegate.findRequestFile(testCaseId, fileId);
    }

    @Override
    public void deleteRequestFile(String testCaseId, String fileId) {
        delegate.deleteRequestFile(testCaseId, fileId);
    }

    @Override
    public List<StudioRequestFile> listRequestFiles(String testCaseId) {
        return delegate.listRequestFiles(testCaseId);
    }

    @Override
    public void assignRequestFiles(String testCaseId, List<String> fileIds) {
        delegate.assignRequestFiles(testCaseId, fileIds);
    }

    @Override
    public void deleteRequestFilesForCase(String testCaseId) {
        delegate.deleteRequestFilesForCase(testCaseId);
    }

    @Override
    public void saveRun(StudioRun run) {
        delegate.saveRun(run);
    }

    @Override
    public Optional<StudioRun> findRun(String runId) {
        return delegate.findRun(runId);
    }

    @Override
    public Optional<StudioRunResult> findRunResult(String runId, String resultId) {
        return delegate.findRunResult(runId, resultId);
    }

    @Override
    public List<StudioRun> listRuns(RunHistoryFilter filter) {
        return delegate.listRuns(filter);
    }

    @Override
    public void deleteRun(String runId) {
        delegate.deleteRun(runId);
    }

    @Override
    public int pruneOldestRuns(String specId, int keep) {
        return delegate.pruneOldestRuns(specId, keep);
    }

    @Override
    public void appendAuditEvent(String eventId, String specId, AuditResourceType resourceType, String resourceId,
                                 AuditAction action, String actorAccountId, String actorLabel, String detail,
                                 Instant occurredAt) {
        delegate.appendAuditEvent(eventId, specId, resourceType, resourceId, action, actorAccountId, actorLabel,
                detail, occurredAt);
    }

    @Override
    public List<AuditEventView> listAuditEvents(AuditResourceType resourceType, String resourceId, int limit) {
        return delegate.listAuditEvents(resourceType, resourceId, limit);
    }

    @Override
    public List<AuditEventView> listAuditEventsForSpec(String specId, int limit) {
        return delegate.listAuditEventsForSpec(specId, limit);
    }

    @Override
    public void appendAuditEventForTenant(String tenantId, String eventId, String specId,
                                          AuditResourceType resourceType, String resourceId,
                                          AuditAction action, String actorAccountId, String actorLabel,
                                          String detail, Instant occurredAt) {
        delegate.appendAuditEventForTenant(tenantId, eventId, specId, resourceType, resourceId, action,
                actorAccountId, actorLabel, detail, occurredAt);
    }

    @Override
    public List<AuditEventView> listAuditEventsForTenant(int limit) {
        return delegate.listAuditEventsForTenant(limit);
    }
}
