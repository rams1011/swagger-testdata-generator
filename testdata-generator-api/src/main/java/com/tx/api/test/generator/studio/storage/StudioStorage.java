package com.tx.api.test.generator.studio.storage;

import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
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

    void saveTestCase(StudioTestCase testCase);

    /**
     * Stores the loaded operation schema for CI without changing the case revision.
     * A null schema clears a copy the current spec no longer describes.
     */
    void updateResponseSchema(String testCaseId, Map<String, Object> schema);

    void deleteTestCase(String testCaseId);

    Optional<StudioTestCase> findTestCase(String testCaseId);

    List<StudioTestCase> listTestCases(String specId, String planId, String operationKey);

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
}
