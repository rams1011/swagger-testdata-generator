package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.tx.api.test.generator.TestdataGenerator;
import com.tx.api.test.generator.generate.GeneratedTestCase;
import com.tx.api.test.generator.model.TestDataRow;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.studio.collection.CollectionDocuments;
import com.tx.api.test.generator.studio.collection.CollectionExporter;
import com.tx.api.test.generator.studio.collection.CollectionImporter;
import com.tx.api.test.generator.studio.collection.ExcelExportPackage;
import com.tx.api.test.generator.studio.collection.EnvironmentExporter;
import com.tx.api.test.generator.studio.collection.EnvironmentImporter;
import com.tx.api.test.generator.studio.collection.EnvironmentMapper;
import com.tx.api.test.generator.studio.collection.EnvironmentOverlay;
import com.tx.api.test.generator.studio.collection.EnvironmentScope;
import com.tx.api.test.generator.studio.collection.StudioEnvironment;
import com.tx.api.test.generator.studio.collection.StudioEnvironmentCaseOverlay;
import com.tx.api.test.generator.studio.dto.CollectionExport;
import com.tx.api.test.generator.studio.dto.CollectionImportResult;
import com.tx.api.test.generator.studio.dto.CopyEnvironmentOverlayRequest;
import com.tx.api.test.generator.studio.dto.EnvironmentExport;
import com.tx.api.test.generator.studio.dto.EnvironmentImportResult;
import com.tx.api.test.generator.studio.dto.EnvironmentSummary;
import com.tx.api.test.generator.studio.dto.SaveEnvironmentRequest;
import com.tx.api.test.generator.studio.dto.ExcelExport;
import com.tx.api.test.generator.studio.dto.ExportEnvironmentRequest;
import com.tx.api.test.generator.studio.dto.ExportRequest;
import com.tx.api.test.generator.studio.dto.GeneratePlansRequest;
import com.tx.api.test.generator.studio.dto.GeneratePlansResult;
import com.tx.api.test.generator.studio.dto.GenerateTestDataRequest;
import com.tx.api.test.generator.studio.dto.GenerateTestDataResult;
import com.tx.api.test.generator.studio.dto.RequestFileView;
import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.dto.RunPlanRequest;
import com.tx.api.test.generator.studio.dto.RunPlanResult;
import com.tx.api.test.generator.studio.dto.RunTestRequest;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.dto.SaveSpecRequest;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;
import com.tx.api.test.generator.studio.dto.SaveTestPlanRequest;
import com.tx.api.test.generator.studio.dto.TestCaseView;
import com.tx.api.test.generator.studio.dto.TestPlanView;
import com.tx.api.test.generator.studio.model.LoadedSpec;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import com.tx.api.test.generator.writer.TestDataExcelWriter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class TestDataStudioService {

    private static final String NA = "NA";
    private static final String DEFAULT_SMOKE_PLAN = "Smoke";
    private static final String DEFAULT_NEGATIVE_PLAN = "Negative";
    private static final long MAX_REQUEST_FILE_BYTES = 20L * 1024 * 1024;
    private static final Gson JSON = new Gson();
    private static final Logger log = LoggerFactory.getLogger(TestDataStudioService.class);

    private final SpecCatalogService specCatalogService;
    private final StudioProperties properties;
    private final StudioStorage studioStorage;
    private final TestRunService testRunService;
    private final RunHistoryService runHistoryService;
    private final TestDataExcelWriter excelWriter = new TestDataExcelWriter();
    private final String serverPort;

    public TestDataStudioService(SpecCatalogService specCatalogService, StudioProperties properties,
                                 StudioStorage studioStorage, TestRunService testRunService,
                                 RunHistoryService runHistoryService,
                                 @Value("${server.port:8083}") String serverPort) {
        this.specCatalogService = specCatalogService;
        this.properties = properties;
        this.studioStorage = studioStorage;
        this.testRunService = testRunService;
        this.runHistoryService = runHistoryService;
        this.serverPort = serverPort;
    }

    public List<TestPlanView> listPlans(String specId) {
        specCatalogService.requireSpec(specId);
        return ensureDefaultPlan(specId).stream().map(this::toPlanView).toList();
    }

    public TestPlanView createPlan(String specId, SaveTestPlanRequest request) {
        specCatalogService.requireSpec(specId);
        ensureDefaultPlan(specId);
        String name = requirePlanName(request == null ? null : request.name());
        rejectDuplicatePlanName(specId, name, null);
        StudioTestPlan plan = new StudioTestPlan();
        plan.setId(UUID.randomUUID().toString());
        plan.setSpecId(specId);
        plan.setName(name);
        plan.setDescription(request == null ? null : StringUtils.trimToNull(request.description()));
        studioStorage.savePlan(plan);
        return toPlanView(plan);
    }

    public TestPlanView updatePlan(String specId, String planId, SaveTestPlanRequest request) {
        StudioTestPlan plan = requirePlan(specId, planId);
        String name = requirePlanName(request == null ? null : request.name());
        rejectDuplicatePlanName(specId, name, planId);
        plan.setName(name);
        plan.setDescription(request == null ? null : StringUtils.trimToNull(request.description()));
        studioStorage.savePlan(plan);
        return toPlanView(plan);
    }

    public void deletePlan(String specId, String planId) {
        requirePlan(specId, planId);
        List<StudioTestPlan> plans = studioStorage.listPlans(specId);
        if (plans.size() <= 1) {
            throw new IllegalArgumentException("A spec must keep at least one test plan");
        }
        studioStorage.deletePlan(planId);
    }

    public TestCaseView create(SaveTestDataRequest request) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setId(UUID.randomUUID().toString());
        apply(testCase, request, true);
        RequestFileSupport.stampTestCaseId(testCase.getRequestBody(), testCase.getId());
        studioStorage.saveTestCase(testCase);
        bindCaseFiles(testCase);
        saveOverlay(testCase, request);
        return toView(testCase, request == null ? null : request.environmentId());
    }

    public TestCaseView update(String id, SaveTestDataRequest request) {
        StudioTestCase testCase = requireCase(id);
        apply(testCase, request, false);
        RequestFileSupport.stampTestCaseId(testCase.getRequestBody(), testCase.getId());
        studioStorage.saveTestCase(testCase);
        bindCaseFiles(testCase);
        saveOverlay(testCase, request);
        return toView(testCase, request == null ? null : request.environmentId());
    }

    public GenerateTestDataResult generate(GenerateTestDataRequest request) {
        if (request == null || StringUtils.isBlank(request.specId())) {
            throw new IllegalArgumentException("specId is required");
        }
        LoadedSpec spec = specCatalogService.requireSpec(request.specId());
        String planId = resolvePlanId(request.specId(), request.planId());
        boolean includePositive = request.includePositive() == null || request.includePositive();
        boolean includeNegative = request.includeNegative() == null || request.includeNegative();
        if (!includePositive && !includeNegative) {
            throw new IllegalArgumentException("Select positive or negative cases to generate");
        }

        List<OperationPage> operations;
        if (StringUtils.isBlank(request.operationKey())) {
            operations = spec.getOperations();
        } else {
            OperationPage operation = spec.getOperation(request.operationKey());
            if (operation == null) {
                throw new NoSuchElementException("Operation not found: " + request.operationKey());
            }
            operations = List.of(operation);
        }

        String environmentName = environmentName(request.specId(), request.environmentId());
        Set<String> existing = new HashSet<>();
        for (StudioTestCase testCase : studioStorage.listTestCases(request.specId(), planId, null)) {
            if (!EnvironmentScope.visible(testCase.getEnvironments(), environmentName)) {
                continue;
            }
            existing.add(caseKey(testCase.getOperationKey(), testCase.getTestName()));
        }

        int created = 0;
        int skipped = 0;
        for (OperationPage operation : operations) {
            for (GeneratedTestCase generated : TestdataGenerator.generateCases(operation)) {
                if ((generated.positive() && !includePositive) || (!generated.positive() && !includeNegative)) {
                    continue;
                }
                String key = caseKey(generated.operationKey(), generated.testName());
                if (!existing.add(key)) {
                    skipped++;
                    continue;
                }
                create(toSaveRequest(request.specId(), planId, generated,
                        membershipFor(request.specId(), request.environmentId(), null),
                        request.environmentId()));
                created++;
            }
        }
        log.info("Generated {} test cases ({} skipped) for spec {} plan {}", created, skipped, request.specId(), planId);
        return new GenerateTestDataResult(created, skipped, operations.size());
    }

    public GeneratePlansResult generatePlans(GeneratePlansRequest request) {
        if (request == null || StringUtils.isBlank(request.specId())) {
            throw new IllegalArgumentException("specId is required");
        }
        specCatalogService.requireSpec(request.specId());
        boolean includeNegative = request.includeNegative() == null || request.includeNegative();
        String smokeName = StringUtils.defaultIfBlank(request.smokePlanName(), DEFAULT_SMOKE_PLAN).trim();
        String negativeName = StringUtils.defaultIfBlank(request.negativePlanName(), DEFAULT_NEGATIVE_PLAN).trim();
        if (includeNegative && smokeName.equalsIgnoreCase(negativeName)) {
            throw new IllegalArgumentException("Smoke and Negative plans must use different names");
        }

        StudioTestPlan smokePlan = findOrCreatePlan(request.specId(), smokeName,
                "Happy path for every operation");
        GenerateTestDataResult smoke = generate(new GenerateTestDataRequest(
                request.specId(), smokePlan.getId(), null, true, false, request.environmentId()));

        TestPlanView negativePlanView = null;
        GenerateTestDataResult negative = null;
        if (includeNegative) {
            StudioTestPlan negativePlan = findOrCreatePlan(request.specId(), negativeName,
                    "Schema-derived negative cases for every operation");
            negative = generate(new GenerateTestDataRequest(
                    request.specId(), negativePlan.getId(), null, false, true, request.environmentId()));
            negativePlanView = toPlanView(negativePlan);
        }
        log.info("Generated plans for spec {}: smoke={} created={} skipped={}; negative={}",
                request.specId(), smokePlan.getName(), smoke.created(), smoke.skipped(),
                includeNegative ? negativeName : "omitted");
        return new GeneratePlansResult(toPlanView(smokePlan), smoke, negativePlanView, negative);
    }

    public void delete(String id) {
        delete(id, null);
    }

    public void delete(String id, String environmentId) {
        StudioTestCase testCase = requireCase(id);
        if (StringUtils.isBlank(environmentId)) {
            removeCase(testCase);
            return;
        }
        StudioEnvironment environment = loadEnvironment(testCase.getSpecId(), environmentId);
        String environmentName = environment == null ? null : environment.name();
        if (StringUtils.isBlank(environmentName)) {
            throw new NoSuchElementException("Environment not found: " + environmentId);
        }
        List<String> names = EnvironmentScope.normalize(testCase.getEnvironments());
        if (names == null) {
            List<String> remaining = otherEnvironmentNames(testCase.getSpecId(), environmentName);
            if (remaining.isEmpty()) {
                removeCase(testCase);
                return;
            }
            testCase.setEnvironments(remaining);
            studioStorage.saveTestCase(testCase);
            clearOverlay(testCase.getSpecId(), environmentId, testCase.getId());
            return;
        }
        if (!EnvironmentScope.visible(names, environmentName)) {
            throw new NoSuchElementException("Test case not found in environment: " + environmentName);
        }
        if (names.size() == 1) {
            removeCase(testCase);
            return;
        }
        testCase.setEnvironments(names.stream()
                .filter(name -> !EnvironmentScope.matchesIgnoreCase(name, environmentName))
                .toList());
        studioStorage.saveTestCase(testCase);
        clearOverlay(testCase.getSpecId(), environmentId, testCase.getId());
    }

    private void removeCase(StudioTestCase testCase) {
        removeOverlays(testCase.getSpecId(), testCase.getId());
        studioStorage.deleteRequestFilesForCase(testCase.getId());
        studioStorage.deleteTestCase(testCase.getId());
    }

    private List<String> otherEnvironmentNames(String specId, String exceptName) {
        List<String> names = new ArrayList<>();
        for (StoredEnvironment stored : studioStorage.listEnvironments(specId)) {
            if (stored == null || StringUtils.isBlank(stored.getName())
                    || EnvironmentScope.matchesIgnoreCase(stored.getName(), exceptName)) {
                continue;
            }
            names.add(stored.getName());
        }
        return EnvironmentScope.normalize(names) == null ? List.of() : EnvironmentScope.normalize(names);
    }

    public TestCaseView get(String id, String environmentId) {
        return toView(requireCase(id), environmentId);
    }

    public List<TestCaseView> list(String specId, String planId, String operationKey, String environmentId) {
        if (StringUtils.isNotBlank(specId) && StringUtils.isNotBlank(planId)) {
            requirePlan(specId, planId);
        }
        StudioEnvironment environment = loadEnvironment(specId, environmentId);
        String environmentName = environment == null ? null : environment.name();
        return studioStorage.listTestCases(specId, planId, operationKey).stream()
                .filter(testCase -> EnvironmentScope.visible(testCase.getEnvironments(), environmentName))
                .map(testCase -> toView(testCase, environment))
                .toList();
    }

    public RunTestResult run(RunTestRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("caseId or testCase is required");
        }
        String environmentId = resolveRunEnvironmentId(request);
        StudioTestCase testCase;
        if (request.testCase() != null) {
            testCase = StringUtils.isNotBlank(request.caseId())
                    ? applyOverlay(requireCase(request.caseId()), environmentId)
                    : new StudioTestCase();
            if (StringUtils.isNotBlank(request.caseId())) {
                testCase.setId(request.caseId());
            }
            apply(testCase, request.testCase(), StringUtils.isBlank(testCase.getId()));
        } else if (StringUtils.isNotBlank(request.caseId())) {
            testCase = applyOverlay(requireCase(request.caseId()), environmentId);
        } else {
            throw new IllegalArgumentException("caseId or testCase is required");
        }
        String trigger = request.testCase() == null
                ? RunHistoryService.TRIGGER_CASE
                : RunHistoryService.TRIGGER_TRY;
        Map<String, String> variables = new LinkedHashMap<>();
        runPrerequisites(testCase, request.auth(), trigger, variables, environmentId);
        RunTestResult result = testRunService.execute(testCase, request.auth(), variables);
        runHistoryService.recordSingle(trigger, testCase, result);
        return result;
    }

    private void runPrerequisites(StudioTestCase testCase, RunAuth auth, String trigger,
                                  Map<String, String> variables, String environmentId) {
        if (testCase == null || CaseVariables.placeholderNames(testCase).isEmpty()) {
            return;
        }
        if (StringUtils.isBlank(testCase.getSpecId()) || StringUtils.isBlank(testCase.getPlanId())) {
            return;
        }
        String environmentName = environmentName(testCase.getSpecId(), environmentId);
        List<StudioTestCase> priors = CaseVariables.prerequisites(
                studioStorage.listTestCases(testCase.getSpecId(), testCase.getPlanId(), null),
                testCase);
        for (StudioTestCase prior : priors) {
            if (!EnvironmentScope.visible(prior.getEnvironments(), environmentName)) {
                continue;
            }
            RunTestResult priorResult = testRunService.execute(applyOverlay(prior, environmentId), auth, variables);
            runHistoryService.recordSingle(trigger, prior, priorResult);
        }
    }

    public RunPlanResult runPlan(RunPlanRequest request) {
        if (request == null || StringUtils.isBlank(request.specId()) || StringUtils.isBlank(request.planId())) {
            throw new IllegalArgumentException("specId and planId are required");
        }
        requirePlan(request.specId(), request.planId());
        List<StudioTestCase> storedCases = studioStorage.listTestCases(request.specId(), request.planId(), null);
        String environmentName = requireEnvironmentNameForScopedCases(
                request.specId(), request.environmentId(), storedCases, "run this plan");
        List<StudioTestCase> cases = CaseVariables.orderForChain(storedCases);
        List<RunTestResult> results = new ArrayList<>();
        Map<String, String> variables = new LinkedHashMap<>();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (StudioTestCase testCase : cases) {
            if ("N".equalsIgnoreCase(StringUtils.trimToEmpty(testCase.getEnabled()))
                    || !EnvironmentScope.visible(testCase.getEnvironments(), environmentName)) {
                results.add(testRunService.skipped(testCase));
                skipped++;
                continue;
            }
            RunTestResult result = testRunService.execute(applyOverlay(testCase, request.environmentId()), request.auth(), variables);
            results.add(result);
            if (result.passed()) {
                passed++;
            } else {
                failed++;
            }
        }
        runHistoryService.recordPlan(request.specId(), request.planId(), results, cases);
        return new RunPlanResult(request.specId(), request.planId(), passed, failed, skipped,
                failed == 0 && passed > 0, results);
    }

    public ExcelExport export(ExportRequest request) {
        if (request == null || StringUtils.isBlank(request.specId())) {
            throw new IllegalArgumentException("specId is required to export test data");
        }
        specCatalogService.requireSpec(request.specId());
        StudioTestPlan plan = requirePlan(request.specId(), resolvePlanId(request.specId(), request.planId()));
        List<StudioTestCase> storedCases = studioStorage.listTestCases(request.specId(), plan.getId(), null);
        String environmentName = requireEnvironmentNameForScopedCases(
                request.specId(), request.environmentId(), storedCases, "export this plan");
        List<StudioTestCase> cases = storedCases.stream()
                .filter(testCase -> EnvironmentScope.visible(testCase.getEnvironments(), environmentName))
                .toList();
        if (cases.isEmpty()) {
            throw new IllegalStateException(environmentName == null
                    ? "No test cases have been saved for this plan yet"
                    : "No test cases in environment '" + environmentName + "' for this plan");
        }

        List<TestDataRow> rows = new ArrayList<>();
        Map<String, byte[]> binaries = new LinkedHashMap<>();
        int testCaseNo = 1;
        for (StudioTestCase testCase : cases) {
            ExportPayload payload = exportPayload(testCase);
            rows.add(toExcelRow(testCaseNo++, testCase, payload.requestBody(), NA, payload.uploadFiles(),
                    payload.contentType()));
            if (payload.files() != null) {
                binaries.putAll(payload.files());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        excelWriter.write(output, rows);
        String fileName = exportFileName(request, plan);
        ExcelExport exported = ExcelExportPackage.toDownload(fileName, output.toByteArray(), binaries, rows.size());
        log.info("Prepared {} api-automation rows for plan {} as {} ({} files)",
                rows.size(), plan.getName(), exported.fileName(), exported.fileCount());
        return exported;
    }

    public CollectionExport exportCollection(ExportRequest request) {
        if (request == null || StringUtils.isBlank(request.specId())) {
            throw new IllegalArgumentException("specId is required to export a collection");
        }
        specCatalogService.requireSpec(request.specId());
        String planId = resolvePlanId(request.specId(), request.planId());
        List<StudioTestCase> storedCases = studioStorage.listTestCases(request.specId(), planId, null);
        String environmentName = requireEnvironmentNameForScopedCases(
                request.specId(), request.environmentId(), storedCases, "export this collection");
        boolean anyVisible = storedCases.stream()
                .anyMatch(testCase -> EnvironmentScope.visible(testCase.getEnvironments(), environmentName));
        if (!anyVisible) {
            throw new IllegalStateException(environmentName == null
                    ? "No test cases have been saved for this plan yet"
                    : "No test cases in environment '" + environmentName + "' for this plan");
        }
        CollectionExport exported = CollectionExporter.export(studioStorage, request.specId(), planId, environmentName);
        log.info("Prepared collection {} ({} cases, {} files)", exported.fileName(), exported.caseCount(),
                exported.fileCount());
        return exported;
    }

    public CollectionImportResult importCollection(byte[] bytes, String fileName, String specId) {
        return importCollection(bytes, fileName, specId, null);
    }

    public CollectionImportResult importCollection(byte[] bytes, String fileName, String specId, String environmentId) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Collection JSON or zip is required");
        }
        if (StringUtils.isNotBlank(specId)) {
            specCatalogService.requireSpec(specId);
        }
        String environmentName = environmentName(specId, environmentId);
        CollectionImportResult imported = CollectionImporter.importInto(
                studioStorage, bytes, fileName, specId, environmentName);
        log.info("Imported collection into spec {} plan {} (created={}, updated={}, files={})",
                imported.specName(), imported.planName(), imported.createdCases(), imported.updatedCases(),
                imported.importedFiles());
        return imported;
    }

    public EnvironmentExport exportEnvironment(ExportEnvironmentRequest request) {
        if (request == null || StringUtils.isBlank(request.specId())) {
            throw new IllegalArgumentException("specId is required to export an environment");
        }
        if (StringUtils.isBlank(request.environmentId())) {
            throw new IllegalArgumentException("environmentId is required to export an environment");
        }
        StoredEnvironment stored = requireEnvironment(request.specId(), request.environmentId());
        var spec = studioStorage.findSpec(request.specId())
                .orElseThrow(() -> new NoSuchElementException("Spec not found: " + request.specId()));
        EnvironmentExport exported = EnvironmentExporter.export(spec, stored);
        log.info("Prepared environment {} for spec {}", exported.fileName(), spec.getName());
        return exported;
    }

    public EnvironmentImportResult importEnvironment(byte[] bytes, String specId, boolean apply) {
        if (StringUtils.isBlank(specId)) {
            throw new IllegalArgumentException("specId is required to import an environment");
        }
        specCatalogService.requireSpec(specId);
        StoredEnvironment stored = EnvironmentImporter.importInto(studioStorage, specId, bytes);
        EnvironmentSummary summary = EnvironmentMapper.toSummary(stored);
        boolean created = stored.getCreatedAt() != null && stored.getUpdatedAt() != null
                && stored.getCreatedAt().equals(stored.getUpdatedAt());
        boolean applied = false;
        if (apply && StringUtils.isNotBlank(summary.baseUrl())) {
            specCatalogService.rename(specId, new SaveSpecRequest(null, null, summary.baseUrl()));
            applied = true;
        }
        log.info("Imported environment {} into spec {} (applied={})", summary.name(), specId, applied);
        return new EnvironmentImportResult(specId, stored.getId(), summary.name(), summary.baseUrl(), applied, created);
    }

    public List<EnvironmentSummary> listEnvironments(String specId) {
        specCatalogService.requireSpec(specId);
        List<StoredEnvironment> stored = studioStorage.listEnvironments(specId);
        if (stored.isEmpty()) {
            var spec = studioStorage.findSpec(specId)
                    .orElseThrow(() -> new NoSuchElementException("Spec not found: " + specId));
            saveEnvironmentDocument(specId, EnvironmentMapper.fromSpec(spec, "local", "NONE"));
            stored = studioStorage.listEnvironments(specId);
        }
        return stored.stream()
                .map(EnvironmentMapper::toSummary)
                .toList();
    }

    public EnvironmentSummary getEnvironment(String specId, String environmentId) {
        return EnvironmentMapper.toSummary(requireEnvironment(specId, environmentId));
    }

    public EnvironmentSummary createEnvironment(String specId, SaveEnvironmentRequest request) {
        specCatalogService.requireSpec(specId);
        String name = requireEnvironmentName(request == null ? null : request.name());
        rejectDuplicateEnvironmentName(specId, name, null);
        String baseUrl = request == null ? null : request.baseUrl();
        if (StringUtils.isBlank(baseUrl)) {
            throw new IllegalArgumentException("Base URL is required");
        }
        StoredEnvironment stored = saveEnvironmentDocument(specId,
                EnvironmentMapper.fromInputs(name, baseUrl, request.authType()));
        log.info("Created environment {} for spec {}", stored.getName(), specId);
        return EnvironmentMapper.toSummary(stored);
    }

    public EnvironmentSummary updateEnvironment(String specId, String environmentId, SaveEnvironmentRequest request) {
        StoredEnvironment stored = requireEnvironment(specId, environmentId);
        String name = requireEnvironmentName(request == null ? null : request.name());
        rejectDuplicateEnvironmentName(specId, name, environmentId);
        StudioEnvironment existing = CollectionDocuments.parseEnvironment(stored.getDocumentJson());
        String previousName = StringUtils.defaultIfBlank(existing == null ? null : existing.name(), stored.getName());
        StudioEnvironment merged = EnvironmentMapper.merge(existing, name,
                request == null ? null : request.baseUrl(),
                request == null ? null : request.authType());
        stored.setName(merged.name());
        stored.setDocumentJson(CollectionDocuments.toJson(merged));
        studioStorage.saveEnvironment(stored);
        retargetEnvironmentName(specId, previousName, merged.name());
        log.info("Updated environment {} for spec {}", stored.getName(), specId);
        return EnvironmentMapper.toSummary(stored);
    }

    public EnvironmentSummary duplicateEnvironment(String specId, String environmentId) {
        StoredEnvironment stored = requireEnvironment(specId, environmentId);
        StudioEnvironment existing = CollectionDocuments.parseEnvironment(stored.getDocumentJson());
        String copyName = EnvironmentMapper.copyName(existing.name(),
                candidate -> studioStorage.findEnvironmentByName(specId, candidate).isPresent());
        StudioEnvironment copy = EnvironmentMapper.merge(existing, copyName, EnvironmentMapper.baseUrl(existing),
                EnvironmentMapper.authType(existing));
        StoredEnvironment created = saveEnvironmentDocument(specId, copy);
        int copied = 0;
        for (StudioTestCase testCase : studioStorage.listTestCases(specId, null, null)) {
            List<String> names = EnvironmentScope.normalize(testCase.getEnvironments());
            if (names == null || names.size() != 1 || !EnvironmentScope.matchesIgnoreCase(names.get(0), existing.name())) {
                continue;
            }
            duplicate(testCase.getId(), new CopyEnvironmentOverlayRequest(environmentId, created.getId()));
            copied++;
        }
        log.info("Duplicated environment {} to {} for spec {} ({} cases)",
                existing.name(), copy.name(), specId, copied);
        return EnvironmentMapper.toSummary(created);
    }

    public EnvironmentImportResult applyEnvironment(String specId, String environmentId) {
        specCatalogService.requireSpec(specId);
        StoredEnvironment stored = studioStorage.findEnvironment(environmentId)
                .orElseThrow(() -> new NoSuchElementException("Environment not found: " + environmentId));
        if (!specId.equals(stored.getSpecId())) {
            throw new NoSuchElementException("Environment not found: " + environmentId);
        }
        EnvironmentSummary summary = EnvironmentMapper.toSummary(stored);
        if (StringUtils.isBlank(summary.baseUrl())) {
            throw new IllegalArgumentException("Environment '" + summary.name() + "' has no values.baseUrl");
        }
        specCatalogService.rename(specId, new SaveSpecRequest(null, null, summary.baseUrl()));
        return new EnvironmentImportResult(specId, stored.getId(), summary.name(), summary.baseUrl(), true, false);
    }

    public void deleteEnvironment(String specId, String environmentId) {
        specCatalogService.requireSpec(specId);
        StoredEnvironment stored = studioStorage.findEnvironment(environmentId)
                .orElseThrow(() -> new NoSuchElementException("Environment not found: " + environmentId));
        if (!specId.equals(stored.getSpecId())) {
            throw new NoSuchElementException("Environment not found: " + environmentId);
        }
        StudioEnvironment environment = CollectionDocuments.parseEnvironment(stored.getDocumentJson());
        String name = environment == null ? null : environment.name();
        studioStorage.deleteEnvironment(environmentId);
        if (StringUtils.isBlank(name)) {
            return;
        }
        for (StudioTestCase testCase : new ArrayList<>(studioStorage.listTestCases(specId, null, null))) {
            List<String> names = testCase.getEnvironments();
            if (names == null || names.isEmpty()) {
                continue;
            }
            boolean onlyThis = names.size() == 1 && EnvironmentScope.matchesIgnoreCase(names.get(0), name);
            if (onlyThis) {
                delete(testCase.getId());
                continue;
            }
            List<String> remaining = names.stream()
                    .filter(item -> !EnvironmentScope.matchesIgnoreCase(item, name))
                    .toList();
            if (remaining.size() != names.size()) {
                testCase.setEnvironments(EnvironmentScope.normalize(remaining));
                studioStorage.saveTestCase(testCase);
            }
        }
    }

    private StoredEnvironment requireEnvironment(String specId, String environmentId) {
        specCatalogService.requireSpec(specId);
        StoredEnvironment stored = studioStorage.findEnvironment(environmentId)
                .orElseThrow(() -> new NoSuchElementException("Environment not found: " + environmentId));
        if (!specId.equals(stored.getSpecId())) {
            throw new NoSuchElementException("Environment not found: " + environmentId);
        }
        return stored;
    }

    private StoredEnvironment saveEnvironmentDocument(String specId, StudioEnvironment environment) {
        return EnvironmentExporter.upsert(studioStorage, specId, environment);
    }

    private static String requireEnvironmentName(String name) {
        String trimmed = StringUtils.trimToNull(name);
        if (trimmed == null) {
            throw new IllegalArgumentException("Environment name is required");
        }
        return trimmed;
    }

    private void rejectDuplicateEnvironmentName(String specId, String name, String excludeId) {
        studioStorage.findEnvironmentByName(specId, name).ifPresent(existing -> {
            if (excludeId == null || !excludeId.equals(existing.getId())) {
                throw new IllegalArgumentException("An environment named '" + name + "' already exists for this spec");
            }
        });
    }

    public String storageType() {
        return properties.getStorage().getType();
    }

    public Map<String, String> runtimeConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("storageType", storageType());
        config.put("saas", String.valueOf(properties.isSaasEnabled()));
        config.put("runHistoryMaxPerSpec", String.valueOf(properties.getRunHistory().resolvedMaxPerSpec()));
        if (properties.isH2Storage()) {
            config.put("h2JdbcUrl", properties.getH2().jdbcUrl());
            config.put("h2Username", properties.getH2().getUsername());
            if (properties.getH2().isConsole()) {
                config.put("h2Console", "http://localhost:" + serverPort + "/h2-console");
            }
        }
        return config;
    }

    public RequestFileView saveRequestFile(String caseId, MultipartFile upload) throws IOException {
        return saveRequestFile(caseId, upload, null);
    }

    public RequestFileView saveRequestFile(String caseId, MultipartFile upload, String partName)
            throws IOException {
        if (upload == null || upload.isEmpty()) {
            throw new IllegalArgumentException("A file is required");
        }
        if (upload.getSize() > MAX_REQUEST_FILE_BYTES) {
            throw new IllegalArgumentException("File is larger than 20MB");
        }
        StudioTestCase testCase = requireCase(caseId);
        StudioRequestFile file = new StudioRequestFile();
        file.setFileId(UUID.randomUUID().toString());
        file.setTestCaseId(testCase.getId());
        file.setFileName(originalFileName(upload.getOriginalFilename()));
        file.setContentType(StringUtils.defaultIfBlank(upload.getContentType(), "application/octet-stream"));
        byte[] content = upload.getBytes();
        file.setContent(content);
        file.setSizeBytes(content.length);
        file.setCreatedAt(Instant.now());
        studioStorage.saveRequestFile(file);
        Object body = RequestFileSupport.putFileRef(testCase.getRequestBody(), partName, file);
        testCase.setRequestBody(body);
        if (!RequestFileSupport.isMultipartContentType(testCase.getContentType())) {
            testCase.setContentType("MULTIPART");
        }
        studioStorage.saveTestCase(testCase);
        bindCaseFiles(testCase, body);
        return toFileView(file);
    }

    public StudioRequestFile requireRequestFile(String caseId, String fileId) {
        requireCase(caseId);
        return studioStorage.findRequestFile(caseId, fileId)
                .orElseThrow(() -> new NoSuchElementException("Request file not found: " + fileId));
    }

    private void apply(StudioTestCase testCase, SaveTestDataRequest request, boolean creating) {
        if (request == null || StringUtils.isBlank(request.specId()) || StringUtils.isBlank(request.operationKey())) {
            throw new IllegalArgumentException("specId and operationKey are required");
        }
        LoadedSpec spec = specCatalogService.requireSpec(request.specId());
        OperationPage operation = spec.getOperation(request.operationKey());
        if (operation == null) {
            throw new NoSuchElementException("Operation not found: " + request.operationKey());
        }

        Map<String, String> pathParams = copyMap(request.pathParams());
        Map<String, String> queryParams = copyMap(request.queryParams());
        Map<String, String> headers = copyMap(request.headers());
        Map<String, String> cookieParams = copyMap(request.cookieParams());

        testCase.setSpecId(request.specId());
        testCase.setPlanId(resolvePlanId(request.specId(), request.planId()));
        testCase.setOperationKey(request.operationKey());
        testCase.setTestName(StringUtils.defaultIfBlank(request.testName(), operation.summary()));
        testCase.setMethod(operation.method());
        testCase.setRawPath(operation.rawPath());
        testCase.setEndpoint(buildEndpoint(operation.rawPath(), pathParams));
        testCase.setBaseUrl(StringUtils.defaultIfBlank(request.baseUrl(), spec.getBaseUrl()));
        Object requestBody = request.requestBody();
        String contentType = StringUtils.defaultIfBlank(request.contentType(),
                operation.defaults() == null ? NA : operation.defaults().contentType());
        if (XmlPayloadSupport.isXml(contentType) && requestBody != null) {
            Map<String, Object> schema = operation.requestBody() == null || operation.requestBody().jsonSchema() == null
                    ? Map.of()
                    : operation.requestBody().jsonSchema();
            requestBody = XmlPayloadSupport.toXml(requestBody, schema);
        }
        testCase.setRequestBody(requestBody);
        if (RequestFileSupport.hasFileRefs(requestBody)
                && (StringUtils.isBlank(contentType)
                || NA.equalsIgnoreCase(contentType)
                || "JSON".equalsIgnoreCase(contentType))) {
            contentType = "MULTIPART";
        }
        testCase.setContentType(contentType);
        testCase.setPathParams(pathParams);
        testCase.setQueryParams(queryParams);
        testCase.setHeaders(headers);
        testCase.setCookieParams(cookieParams);
        if (operation.response() != null) {
            testCase.setResponseJsonSchema(operation.response().jsonSchema());
            testCase.setResponseType(StringUtils.defaultIfBlank(request.responseType(), operation.response().responseType()));
            testCase.setStatusCode(request.statusCode() == null ? operation.response().statusCode() : request.statusCode());
            testCase.setJsonPathAssertions(StringUtils.defaultIfBlank(request.jsonPathAssertions(), NA));
        } else if (creating) {
            testCase.setStatusCode(request.statusCode() == null ? 200 : request.statusCode());
            testCase.setResponseType(StringUtils.defaultIfBlank(request.responseType(), "NONE"));
            testCase.setJsonPathAssertions(StringUtils.defaultIfBlank(request.jsonPathAssertions(), NA));
        }
        if (request.statusCode() != null) {
            testCase.setStatusCode(request.statusCode());
        }
        if (StringUtils.isNotBlank(request.responseType())) {
            testCase.setResponseType(request.responseType());
        }
        testCase.setExpectedResponse(StringUtils.defaultIfBlank(request.expectedResponse(), NA));
        testCase.setEnabled(StringUtils.defaultIfBlank(request.enabled(), "Y"));
        testCase.setTestGroup(StringUtils.defaultIfBlank(request.testGroup(), operation.tag()));
        if (StringUtils.isNotBlank(request.jsonPathAssertions())) {
            testCase.setJsonPathAssertions(request.jsonPathAssertions());
        }
        testCase.setExtracts(StringUtils.defaultIfBlank(request.extracts(), NA));
        testCase.setMaxResponseTimeMs(request.maxResponseTimeMs() == null ? 3000L : request.maxResponseTimeMs());
        testCase.setEnvironments(membershipFor(request.specId(), request.environmentId(), request.environments()));
    }

    private SaveTestDataRequest toSaveRequest(String specId, String planId, GeneratedTestCase generated,
                                              List<String> environments, String environmentId) {
        return new SaveTestDataRequest(
                specId,
                planId,
                generated.operationKey(),
                generated.testName(),
                null,
                generated.pathParams(),
                generated.queryParams(),
                generated.headers(),
                generated.cookieParams(),
                generated.requestBody(),
                generated.statusCode(),
                generated.responseType(),
                generated.expectedResponse(),
                generated.enabled(),
                generated.testGroup(),
                generated.jsonPathAssertions(),
                NA,
                generated.maxResponseTimeMs(),
                generated.contentType(),
                environmentId,
                EnvironmentScope.normalize(environments)
        );
    }

    private String caseKey(String operationKey, String testName) {
        return StringUtils.defaultString(operationKey) + "\0"
                + StringUtils.defaultString(testName).toLowerCase(Locale.ROOT);
    }

    private String inlineRequestBody(StudioTestCase testCase) {
        if (testCase.getRequestBody() == null || NA.equalsIgnoreCase(String.valueOf(testCase.getContentType()))) {
            return NA;
        }
        if (HttpPayloadSupport.isFormUrlEncoded(testCase.getContentType())) {
            String encoded = HttpPayloadSupport.formUrlEncoded(testCase.getRequestBody());
            return StringUtils.isBlank(encoded) ? NA : encoded;
        }
        if (XmlPayloadSupport.isXml(testCase.getContentType())) {
            String xml = XmlPayloadSupport.toXml(testCase.getRequestBody(), Map.of());
            return StringUtils.isBlank(xml) ? NA : xml;
        }
        return inlineJson(testCase.getRequestBody());
    }

    private ExportPayload exportPayload(StudioTestCase testCase) {
        List<RequestFileSupport.FilePart> files = RequestFileSupport.collectFileParts(testCase.getRequestBody());
        if (files.isEmpty()) {
            String contentType = blankToNa(testCase.getContentType());
            if (HttpPayloadSupport.isFormUrlEncoded(testCase.getContentType())) {
                contentType = "application/x-www-form-urlencoded";
            } else if (XmlPayloadSupport.isXml(testCase.getContentType()) && !contentType.contains("/")) {
                contentType = "application/xml";
            }
            return new ExportPayload(contentType, inlineRequestBody(testCase), NA, Map.of());
        }
        String requestBody = inlineJson(RequestFileSupport.stripFileRefs(testCase.getRequestBody()));
        List<String> entries = new ArrayList<>();
        Map<String, byte[]> binaries = new LinkedHashMap<>();
        for (RequestFileSupport.FilePart part : files) {
            StudioRequestFile stored = studioStorage.findRequestFile(testCase.getId(), part.fileId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Saved file for field '" + part.fieldName() + "' is missing. Re-upload it before export."));
            String relative = ExcelExportPackage.relativeFilePath(
                    testCase.getId(), stored.getFileId(), stored.getFileName());
            binaries.put(relative, stored.getContent() == null ? new byte[0] : stored.getContent());
            entries.add(ExcelExportPackage.uploadReference(
                    part.fieldName(), testCase.getId(), stored.getFileId(), stored.getFileName()));
        }
        return new ExportPayload("MULTIPART", requestBody, String.join("|", entries), binaries);
    }

    private String inlineJson(Object body) {
        if (body == null) {
            return NA;
        }
        if (body instanceof Map<?, ?> map && map.isEmpty()) {
            return NA;
        }
        if (body instanceof String text) {
            return StringUtils.isBlank(text) || NA.equalsIgnoreCase(text.trim()) ? NA : text;
        }
        String json = JSON.toJson(body);
        return StringUtils.isBlank(json) || "{}".equals(json) ? NA : json;
    }

    private String exportFileName(ExportRequest request, StudioTestPlan plan) {
        String name = request == null ? null : request.excelFileName();
        if (StringUtils.isBlank(name) && plan != null) {
            name = plan.getName();
        }
        name = StringUtils.defaultIfBlank(name, properties.getDefaultExcelFileName());
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isBlank()) {
            name = "TestData.xlsx";
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            name = name + ".xlsx";
        }
        return name;
    }

    private TestDataRow toExcelRow(int testCaseNo, StudioTestCase testCase, String payloadReference,
                                  String schemaReference, String uploadFiles, String contentType) {
        Map<String, String> headers = new LinkedHashMap<>(copyMap(testCase.getHeaders()));
        String cookie = HttpPayloadSupport.cookieHeader(testCase.getCookieParams());
        if (StringUtils.isNotBlank(cookie) && headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Cookie"))) {
            headers.put("Cookie", cookie);
        }
        String downloadPath = "FILE".equalsIgnoreCase(StringUtils.trimToEmpty(testCase.getResponseType()))
                ? "downloads/" + testCase.getId()
                : NA;
        return TestDataRow.builder()
                .testCaseNo(testCaseNo)
                .serviceName(testCase.getTestName())
                .serviceBaseUrl(testCase.getBaseUrl())
                .requestType(testCase.getMethod())
                .endpoint(testCase.getEndpoint())
                .contentType(blankToNa(contentType))
                .queryParamKey(HttpPayloadSupport.joinKeys(testCase.getQueryParams()))
                .queryParamValue(HttpPayloadSupport.joinValues(testCase.getQueryParams()))
                .headerName(HttpPayloadSupport.joinKeys(headers))
                .headerValue(HttpPayloadSupport.joinValues(headers))
                .requestBody(payloadReference)
                .statusCode(testCase.getStatusCode())
                .responseType(blankToNa(testCase.getResponseType()))
                .expectedResponse(blankToNa(testCase.getExpectedResponse()))
                .enabled(blankToNa(testCase.getEnabled()))
                .testGroup(blankToNa(testCase.getTestGroup()))
                .jsonPathAssertions(blankToNa(testCase.getJsonPathAssertions()))
                .extracts(blankToNa(testCase.getExtracts()))
                .jsonSchemaPath(schemaReference)
                .maxResponseTimeMs(testCase.getMaxResponseTimeMs())
                .uploadFiles(blankToNa(uploadFiles))
                .downloadFilePath(blankToNa(downloadPath))
                .build();
    }

    private TestCaseView toView(StudioTestCase testCase) {
        return new TestCaseView(
                testCase.getId(),
                testCase.getSpecId(),
                testCase.getPlanId(),
                testCase.getOperationKey(),
                testCase.getTestName(),
                testCase.getMethod(),
                testCase.getRawPath(),
                testCase.getEndpoint(),
                testCase.getBaseUrl(),
                testCase.getContentType(),
                testCase.getPathParams(),
                testCase.getQueryParams(),
                testCase.getHeaders(),
                testCase.getCookieParams(),
                testCase.getRequestBody(),
                testCase.getStatusCode(),
                testCase.getResponseType(),
                testCase.getExpectedResponse(),
                testCase.getEnabled(),
                testCase.getTestGroup(),
                testCase.getJsonPathAssertions(),
                testCase.getExtracts(),
                testCase.getMaxResponseTimeMs(),
                false,
                testCase.getEnvironments()
        );
    }

    private TestCaseView toView(StudioTestCase testCase, String environmentId) {
        return toView(testCase, loadEnvironment(testCase == null ? null : testCase.getSpecId(), environmentId));
    }

    private TestCaseView toView(StudioTestCase testCase, StudioEnvironment environment) {
        boolean hasOverlay = EnvironmentOverlay.hasOverlay(environment, testCase.getId());
        StudioTestCase view = hasOverlay
                ? EnvironmentOverlay.merge(testCase, environment.caseOverlays().get(testCase.getId()))
                : testCase;
        return new TestCaseView(
                view.getId(),
                view.getSpecId(),
                view.getPlanId(),
                view.getOperationKey(),
                view.getTestName(),
                view.getMethod(),
                view.getRawPath(),
                view.getEndpoint(),
                view.getBaseUrl(),
                view.getContentType(),
                view.getPathParams(),
                view.getQueryParams(),
                view.getHeaders(),
                view.getCookieParams(),
                view.getRequestBody(),
                view.getStatusCode(),
                view.getResponseType(),
                view.getExpectedResponse(),
                view.getEnabled(),
                view.getTestGroup(),
                view.getJsonPathAssertions(),
                view.getExtracts(),
                view.getMaxResponseTimeMs(),
                hasOverlay,
                testCase.getEnvironments()
        );
    }

    public TestCaseView duplicate(String caseId, CopyEnvironmentOverlayRequest request) {
        if (request == null || StringUtils.isBlank(request.toEnvironmentId())) {
            throw new IllegalArgumentException("toEnvironmentId is required");
        }
        StudioTestCase source = applyOverlay(requireCase(caseId), request.fromEnvironmentId());
        StoredEnvironment targetStored = requireEnvironment(source.getSpecId(), request.toEnvironmentId());
        StudioEnvironment target = CollectionDocuments.parseEnvironment(targetStored.getDocumentJson());
        StudioTestCase copy = EnvironmentOverlay.copy(source);
        copy.setId(UUID.randomUUID().toString());
        copy.setEnvironments(EnvironmentScope.normalize(List.of(target.name())));
        copy.setRequestBody(copyRequestFiles(source.getId(), copy.getId(), source.getRequestBody()));
        RequestFileSupport.stampTestCaseId(copy.getRequestBody(), copy.getId());
        studioStorage.saveTestCase(copy);
        bindCaseFiles(copy);
        return toView(copy, request.toEnvironmentId());
    }

    private void saveOverlay(StudioTestCase testCase, SaveTestDataRequest request) {
        if (testCase == null || request == null || StringUtils.isBlank(request.environmentId())) {
            return;
        }
        clearOverlay(testCase.getSpecId(), request.environmentId(), testCase.getId());
    }

    private static String resolveRunEnvironmentId(RunTestRequest request) {
        if (request == null) {
            return null;
        }
        if (StringUtils.isNotBlank(request.environmentId())) {
            return request.environmentId();
        }
        return request.testCase() == null ? null : request.testCase().environmentId();
    }

    private StudioTestCase applyOverlay(StudioTestCase testCase, String environmentId) {
        StudioEnvironmentCaseOverlay overlay = overlayOf(
                testCase == null ? null : testCase.getSpecId(), environmentId,
                testCase == null ? null : testCase.getId());
        return overlay == null ? testCase : EnvironmentOverlay.merge(testCase, overlay);
    }

    private StudioEnvironmentCaseOverlay overlayOf(String specId, String environmentId, String caseId) {
        StudioEnvironment environment = loadEnvironment(specId, environmentId);
        if (environment == null || StringUtils.isBlank(caseId)) {
            return null;
        }
        return environment.caseOverlays().get(caseId);
    }

    private StudioEnvironment loadEnvironment(String specId, String environmentId) {
        if (StringUtils.isBlank(specId) || StringUtils.isBlank(environmentId)) {
            return null;
        }
        StoredEnvironment stored;
        try {
            stored = requireEnvironment(specId, environmentId);
        } catch (NoSuchElementException ignored) {
            return null;
        }
        return CollectionDocuments.parseEnvironment(stored.getDocumentJson());
    }

    private void upsertOverlay(String specId, String environmentId, String caseId,
                               StudioEnvironmentCaseOverlay overlay) {
        StoredEnvironment stored = requireEnvironment(specId, environmentId);
        StudioEnvironment environment = CollectionDocuments.parseEnvironment(stored.getDocumentJson());
        StudioEnvironment next = EnvironmentOverlay.withCase(environment, caseId, overlay);
        stored.setDocumentJson(CollectionDocuments.toJson(next));
        studioStorage.saveEnvironment(stored);
    }

    private void clearOverlay(String specId, String environmentId, String caseId) {
        if (StringUtils.isBlank(specId) || StringUtils.isBlank(environmentId) || StringUtils.isBlank(caseId)) {
            return;
        }
        StoredEnvironment stored;
        try {
            stored = requireEnvironment(specId, environmentId);
        } catch (NoSuchElementException ignored) {
            return;
        }
        StudioEnvironment environment = CollectionDocuments.parseEnvironment(stored.getDocumentJson());
        if (!EnvironmentOverlay.hasOverlay(environment, caseId)) {
            return;
        }
        stored.setDocumentJson(CollectionDocuments.toJson(EnvironmentOverlay.withoutCase(environment, caseId)));
        studioStorage.saveEnvironment(stored);
    }

    private void retargetEnvironmentName(String specId, String from, String to) {
        if (StringUtils.isBlank(specId) || StringUtils.isBlank(from) || StringUtils.isBlank(to)
                || from.trim().equals(to.trim())) {
            return;
        }
        for (StudioTestCase testCase : studioStorage.listTestCases(specId, null, null)) {
            List<String> current = EnvironmentScope.normalize(testCase.getEnvironments());
            List<String> renamed = EnvironmentScope.rename(testCase.getEnvironments(), from, to);
            if (!java.util.Objects.equals(current, renamed)) {
                testCase.setEnvironments(renamed);
                studioStorage.saveTestCase(testCase);
            }
        }
    }

    private List<String> membershipFor(String specId, String environmentId, List<String> fallback) {
        StudioEnvironment environment = loadEnvironment(specId, environmentId);
        if (environment != null && StringUtils.isNotBlank(environment.name())) {
            return EnvironmentScope.normalize(List.of(environment.name()));
        }
        return EnvironmentScope.normalize(fallback);
    }

    private Object copyRequestFiles(String fromCaseId, String toCaseId, Object body) {
        List<StudioRequestFile> files = studioStorage.listRequestFiles(fromCaseId);
        if (files == null || files.isEmpty()) {
            return body;
        }
        Map<String, String> oldToNew = new LinkedHashMap<>();
        for (StudioRequestFile file : files) {
            StudioRequestFile copy = studioStorage.findRequestFile(fromCaseId, file.getFileId()).orElse(null);
            if (copy == null) {
                continue;
            }
            String newId = UUID.randomUUID().toString();
            oldToNew.put(file.getFileId(), newId);
            StudioRequestFile stored = new StudioRequestFile();
            stored.setFileId(newId);
            stored.setTestCaseId(toCaseId);
            stored.setFileName(copy.getFileName());
            stored.setContentType(copy.getContentType());
            stored.setSizeBytes(copy.getSizeBytes());
            stored.setContent(copy.getContent());
            stored.setCreatedAt(Instant.now());
            studioStorage.saveRequestFile(stored);
        }
        return RequestFileSupport.replaceFileIds(body, oldToNew, toCaseId);
    }

    private void removeOverlays(String specId, String caseId) {
        if (StringUtils.isBlank(specId) || StringUtils.isBlank(caseId)) {
            return;
        }
        for (StoredEnvironment stored : studioStorage.listEnvironments(specId)) {
            StudioEnvironment environment = CollectionDocuments.parseEnvironment(stored.getDocumentJson());
            if (!EnvironmentOverlay.hasOverlay(environment, caseId)) {
                continue;
            }
            stored.setDocumentJson(CollectionDocuments.toJson(EnvironmentOverlay.withoutCase(environment, caseId)));
            studioStorage.saveEnvironment(stored);
        }
    }

    private List<StudioTestPlan> ensureDefaultPlan(String specId) {
        List<StudioTestPlan> plans = new ArrayList<>(studioStorage.listPlans(specId));
        if (!plans.isEmpty()) {
            backfillOrphanCases(specId, plans.get(0));
            return plans;
        }
        StudioTestPlan plan = new StudioTestPlan();
        plan.setId(UUID.randomUUID().toString());
        plan.setSpecId(specId);
        plan.setName("Default");
        studioStorage.savePlan(plan);
        backfillOrphanCases(specId, plan);
        return studioStorage.listPlans(specId);
    }

    private void backfillOrphanCases(String specId, StudioTestPlan plan) {
        for (StudioTestCase testCase : studioStorage.listTestCases(specId, null, null)) {
            if (StringUtils.isBlank(testCase.getPlanId())) {
                testCase.setPlanId(plan.getId());
                studioStorage.saveTestCase(testCase);
            }
        }
    }

    private String resolvePlanId(String specId, String planId) {
        List<StudioTestPlan> plans = ensureDefaultPlan(specId);
        if (StringUtils.isNotBlank(planId)) {
            requirePlan(specId, planId);
            return planId;
        }
        return plans.get(0).getId();
    }

    private StudioTestPlan requirePlan(String specId, String planId) {
        if (StringUtils.isBlank(planId)) {
            throw new IllegalArgumentException("planId is required");
        }
        StudioTestPlan plan = studioStorage.findPlan(planId)
                .orElseThrow(() -> new NoSuchElementException("Test plan not found: " + planId));
        if (!specId.equals(plan.getSpecId())) {
            throw new IllegalArgumentException("Test plan does not belong to this spec");
        }
        return plan;
    }

    private String requirePlanName(String name) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Plan name is required");
        }
        return name.trim();
    }

    private StudioTestPlan findOrCreatePlan(String specId, String name, String description) {
        ensureDefaultPlan(specId);
        return studioStorage.listPlans(specId).stream()
                .filter(plan -> name.equalsIgnoreCase(plan.getName()))
                .findFirst()
                .orElseGet(() -> {
                    StudioTestPlan plan = new StudioTestPlan();
                    plan.setId(UUID.randomUUID().toString());
                    plan.setSpecId(specId);
                    plan.setName(name);
                    plan.setDescription(description);
                    studioStorage.savePlan(plan);
                    return plan;
                });
    }

    private void rejectDuplicatePlanName(String specId, String name, String excludePlanId) {
        boolean duplicate = studioStorage.listPlans(specId).stream()
                .anyMatch(plan -> !plan.getId().equals(excludePlanId)
                        && name.equalsIgnoreCase(plan.getName()));
        if (duplicate) {
            throw new IllegalArgumentException("A plan named '" + name + "' already exists for this spec");
        }
    }

    private TestPlanView toPlanView(StudioTestPlan plan) {
        return new TestPlanView(plan.getId(), plan.getSpecId(), plan.getName(), plan.getDescription());
    }

    private StudioTestCase requireCase(String id) {
        return studioStorage.findTestCase(id)
                .orElseThrow(() -> new NoSuchElementException("Test case not found: " + id));
    }

    private String buildEndpoint(String rawPath, Map<String, String> pathParams) {
        String endpoint = rawPath;
        for (Map.Entry<String, String> entry : pathParams.entrySet()) {
            endpoint = endpoint.replace("{" + entry.getKey() + "}", StringUtils.defaultString(entry.getValue()));
        }
        return endpoint;
    }

    private Map<String, String> copyMap(Map<String, String> source) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (StringUtils.isNotBlank(key) && value != null) {
                    copy.put(key, value);
                }
            });
        }
        return copy;
    }

    private String blankToNa(String value) {
        return StringUtils.isBlank(value) ? NA : value;
    }

    private RequestFileView toFileView(StudioRequestFile file) {
        return new RequestFileView(
                file.getFileId(),
                file.getTestCaseId(),
                file.getFileName(),
                file.getContentType(),
                file.getSizeBytes(),
                file.getCreatedAt()
        );
    }

    private void bindCaseFiles(StudioTestCase testCase) {
        bindCaseFiles(testCase, null);
    }

    private void bindCaseFiles(StudioTestCase testCase, Object extraBody) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(RequestFileSupport.fileIds(testCase.getRequestBody()));
        ids.addAll(RequestFileSupport.fileIds(extraBody));
        for (StudioRequestFile existing : studioStorage.listRequestFiles(testCase.getId())) {
            if (existing != null && StringUtils.isNotBlank(existing.getFileId())) {
                ids.add(existing.getFileId());
            }
        }
        studioStorage.assignRequestFiles(testCase.getId(), List.copyOf(ids));
    }

    private String environmentName(String specId, String environmentId) {
        StudioEnvironment environment = loadEnvironment(specId, environmentId);
        return environment == null ? null : environment.name();
    }

    private String requireEnvironmentNameForScopedCases(String specId, String environmentId,
                                                       List<StudioTestCase> cases, String action) {
        boolean scoped = cases != null && cases.stream()
                .anyMatch(testCase -> EnvironmentScope.assigned(testCase.getEnvironments()));
        String name = environmentName(specId, environmentId);
        if (scoped && StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("environmentId is required to " + action);
        }
        return name;
    }

    private String originalFileName(String name) {
        String base = StringUtils.defaultIfBlank(name, "upload.bin").replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        return StringUtils.defaultIfBlank(base, "upload.bin");
    }

    private record ExportPayload(String contentType, String requestBody, String uploadFiles,
                                 Map<String, byte[]> files) {
    }
}
