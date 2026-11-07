package com.tx.api.test.generator.studio.storage;

import com.tx.api.test.generator.studio.dto.CaseLastRunView;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioCaseDraft;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioRun;
import com.tx.api.test.generator.studio.model.StudioRunResult;
import com.tx.api.test.generator.studio.audit.AuditAction;
import com.tx.api.test.generator.studio.audit.AuditResourceType;
import com.tx.api.test.generator.studio.dto.AuditEventView;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface StudioStorage {

    void saveSpec(StoredSpec spec);

    Optional<StoredSpec> findSpec(String specId);

    List<StoredSpec> listSpecs();

    /** Distinct raw {@code td_spec.label} values for the current tenant (may be null or blank). */
    default List<String> listDistinctSpecLabels() {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (StoredSpec spec : listSpecs()) {
            String label = spec.getLabel();
            seen.add(label == null ? "" : label);
        }
        return new java.util.ArrayList<>(seen);
    }

    default List<StoredSpec> listSpecs(SpecListFilter filter) {
        return listSpecs();
    }

    default long countSpecs(SpecListFilter filter) {
        return listSpecs(filter).size();
    }

    void deleteSpec(String specId);

    default void ensureSpecNameUniqueIndex() {
    }

    void savePlan(StudioTestPlan plan);

    void deletePlan(String planId);

    Optional<StudioTestPlan> findPlan(String planId);

    List<StudioTestPlan> listPlans(String specId);

    default List<StoredEnvironment> listEnvironments(String specId) {
        return List.of();
    }

    default Optional<StoredEnvironment> findEnvironment(String environmentId) {
        return Optional.empty();
    }

    default Optional<StoredEnvironment> findEnvironmentByName(String specId, String name) {
        if (name == null) {
            return Optional.empty();
        }
        return listEnvironments(specId).stream()
                .filter(environment -> name.equalsIgnoreCase(environment.getName()))
                .findFirst();
    }

    default void saveEnvironment(StoredEnvironment environment) {
        throw new IllegalStateException("Environment files need H2 or database storage");
    }

    default void deleteEnvironment(String environmentId) {
        throw new java.util.NoSuchElementException("Environment not found: " + environmentId);
    }

    /** Workspace-wide variables document for the current tenant, as JSON. */
    default Optional<String> findGlobalVariables() {
        return Optional.empty();
    }

    default void saveGlobalVariables(String documentJson) {
        throw new IllegalStateException("Global variables need H2 or database storage");
    }

    void saveTestCase(StudioTestCase testCase);

    /**
     * Stores the loaded operation schema for CI without changing the case revision.
     * A null schema clears a copy the current spec no longer describes.
     */
    void updateResponseSchema(String testCaseId, Map<String, Object> schema);

    void deleteTestCase(String testCaseId);

    Optional<StudioTestCase> findTestCase(String testCaseId);

    List<StudioTestCase> listTestCases(String specId, String planId, String operationKey);

    /** Number of cases in a spec (all plans). */
    default int countTestCases(String specId) {
        return listTestCases(specId, null, null).size();
    }

    Optional<StudioCaseDraft> findCaseDraft(String testCaseId);

    List<StudioCaseDraft> listCaseDrafts(String specId);

    void saveCaseDraft(StudioCaseDraft draft);

    void deleteCaseDraft(String testCaseId);

    void saveRequestFile(StudioRequestFile file);

    Optional<StudioRequestFile> findRequestFile(String testCaseId, String fileId);

    void deleteRequestFile(String testCaseId, String fileId);

    List<StudioRequestFile> listRequestFiles(String testCaseId);

    void assignRequestFiles(String testCaseId, List<String> fileIds);

    void deleteRequestFilesForCase(String testCaseId);

    void saveRun(StudioRun run);

    Optional<StudioRun> findRun(String runId);

    Optional<StudioRunResult> findRunResult(String runId, String resultId);

    List<StudioRun> listRuns(RunHistoryFilter filter);

    void deleteRun(String runId);

    /**
     * The newest recorded result per case for a spec (optionally one plan and environment), looking at the newest
     * {@code scanLimit} results only.
     */
    default List<CaseLastRunView> latestCaseRuns(String specId, String planId, String environmentId, int scanLimit) {
        return List.of();
    }

    default int pruneOldestRuns(String specId, int keep) {
        return 0;
    }

    default void appendAuditEvent(String eventId, String specId, AuditResourceType resourceType, String resourceId,
                                  AuditAction action, String actorAccountId, String actorLabel, String detail,
                                  Instant occurredAt) {
    }

    default List<AuditEventView> listAuditEvents(AuditResourceType resourceType, String resourceId, int limit) {
        return List.of();
    }

    default List<AuditEventView> listAuditEventsForSpec(String specId, int limit) {
        return List.of();
    }

    /**
     * Writes an event for a workspace that is not the caller's current one.
     * Sign-in has no workspace context yet, and an access request targets another workspace.
     */
    default void appendAuditEventForTenant(String tenantId, String eventId, String specId,
                                           AuditResourceType resourceType, String resourceId,
                                           AuditAction action, String actorAccountId, String actorLabel,
                                           String detail, Instant occurredAt) {
    }

    default List<AuditEventView> listAuditEventsForTenant(int limit) {
        return List.of();
    }

    /** Last audited private target allowlist per environment id, one entry per line. Not tenant scoped. */
    default Map<String, String> listPrivateTargetAllowlists() {
        return Map.of();
    }

    default void savePrivateTargetAllowlist(String environmentId, String entries) {
    }

    default void deletePrivateTargetAllowlist(String environmentId) {
    }

    /** Looks across tenants, for server-level work that has no tenant of its own. */
    default Optional<EnvironmentOwner> findEnvironmentOwner(String environmentId) {
        return Optional.empty();
    }

    record EnvironmentOwner(String tenantId, String name) {
    }
}
