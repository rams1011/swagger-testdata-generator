package com.tx.api.test.generator.studio.storage;

import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioRun;
import com.tx.api.test.generator.studio.model.StudioRunResult;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;

import java.util.List;
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

    void saveTestCase(StudioTestCase testCase);

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
}
