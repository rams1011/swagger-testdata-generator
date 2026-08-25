package com.tx.api.test.generator.studio.storage;

import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;

import java.util.List;
import java.util.Optional;

public interface StudioStorage {

    void saveSpec(StoredSpec spec);

    Optional<StoredSpec> findSpec(String specId);

    List<StoredSpec> listSpecs();

    void savePlan(StudioTestPlan plan);

    void deletePlan(String planId);

    Optional<StudioTestPlan> findPlan(String planId);

    List<StudioTestPlan> listPlans(String specId);

    void saveTestCase(StudioTestCase testCase);

    void deleteTestCase(String testCaseId);

    Optional<StudioTestCase> findTestCase(String testCaseId);

    List<StudioTestCase> listTestCases(String specId, String planId, String operationKey);
}
