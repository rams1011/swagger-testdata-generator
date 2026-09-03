package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.tx.api.test.generator.TestdataGenerator;
import com.tx.api.test.generator.generate.GeneratedTestCase;
import com.tx.api.test.generator.model.TestDataRow;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.studio.dto.ExcelExport;
import com.tx.api.test.generator.studio.dto.ExportRequest;
import com.tx.api.test.generator.studio.dto.GeneratePlansRequest;
import com.tx.api.test.generator.studio.dto.GeneratePlansResult;
import com.tx.api.test.generator.studio.dto.GenerateTestDataRequest;
import com.tx.api.test.generator.studio.dto.GenerateTestDataResult;
import com.tx.api.test.generator.studio.dto.RequestFileView;
import com.tx.api.test.generator.studio.dto.RunPlanRequest;
import com.tx.api.test.generator.studio.dto.RunPlanResult;
import com.tx.api.test.generator.studio.dto.RunTestRequest;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;
import com.tx.api.test.generator.studio.dto.SaveTestPlanRequest;
import com.tx.api.test.generator.studio.dto.TestCaseView;
import com.tx.api.test.generator.studio.dto.TestPlanView;
import com.tx.api.test.generator.studio.model.LoadedSpec;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        return toView(testCase);
    }

    public TestCaseView update(String id, SaveTestDataRequest request) {
        StudioTestCase testCase = requireCase(id);
        apply(testCase, request, false);
        RequestFileSupport.stampTestCaseId(testCase.getRequestBody(), testCase.getId());
        studioStorage.saveTestCase(testCase);
        bindCaseFiles(testCase);
        return toView(testCase);
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

        Set<String> existing = new HashSet<>();
        for (StudioTestCase testCase : studioStorage.listTestCases(request.specId(), planId, null)) {
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
                create(toSaveRequest(request.specId(), planId, generated));
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
                request.specId(), smokePlan.getId(), null, true, false));

        TestPlanView negativePlanView = null;
        GenerateTestDataResult negative = null;
        if (includeNegative) {
            StudioTestPlan negativePlan = findOrCreatePlan(request.specId(), negativeName,
                    "Schema-derived negative cases for every operation");
            negative = generate(new GenerateTestDataRequest(
                    request.specId(), negativePlan.getId(), null, false, true));
            negativePlanView = toPlanView(negativePlan);
        }
        log.info("Generated plans for spec {}: smoke={} created={} skipped={}; negative={}",
                request.specId(), smokePlan.getName(), smoke.created(), smoke.skipped(),
                includeNegative ? negativeName : "omitted");
        return new GeneratePlansResult(toPlanView(smokePlan), smoke, negativePlanView, negative);
    }

    public void delete(String id) {
        studioStorage.deleteRequestFilesForCase(id);
        studioStorage.deleteTestCase(id);
    }

    public TestCaseView get(String id) {
        return toView(requireCase(id));
    }

    public List<TestCaseView> list(String specId, String planId, String operationKey) {
        if (StringUtils.isNotBlank(specId) && StringUtils.isNotBlank(planId)) {
            requirePlan(specId, planId);
        }
        return studioStorage.listTestCases(specId, planId, operationKey).stream()
                .map(this::toView)
                .toList();
    }

    public RunTestResult run(RunTestRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("caseId or testCase is required");
        }
        StudioTestCase testCase;
        if (StringUtils.isNotBlank(request.caseId())) {
            testCase = requireCase(request.caseId());
        } else if (request.testCase() != null) {
            testCase = new StudioTestCase();
            apply(testCase, request.testCase(), true);
        } else {
            throw new IllegalArgumentException("caseId or testCase is required");
        }
        String trigger = StringUtils.isNotBlank(request.caseId())
                ? RunHistoryService.TRIGGER_CASE
                : RunHistoryService.TRIGGER_TRY;
        RunTestResult result = testRunService.execute(testCase, request.auth());
        runHistoryService.recordSingle(trigger, testCase, result);
        return result;
    }

    public RunPlanResult runPlan(RunPlanRequest request) {
        if (request == null || StringUtils.isBlank(request.specId()) || StringUtils.isBlank(request.planId())) {
            throw new IllegalArgumentException("specId and planId are required");
        }
        requirePlan(request.specId(), request.planId());
        List<StudioTestCase> cases = studioStorage.listTestCases(request.specId(), request.planId(), null);
        List<RunTestResult> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (StudioTestCase testCase : cases) {
            if ("N".equalsIgnoreCase(StringUtils.trimToEmpty(testCase.getEnabled()))) {
                results.add(testRunService.skipped(testCase));
                skipped++;
                continue;
            }
            RunTestResult result = testRunService.execute(testCase, request.auth());
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
        List<StudioTestCase> cases = studioStorage.listTestCases(request.specId(), plan.getId(), null);
        if (cases.isEmpty()) {
            throw new IllegalStateException("No test cases have been saved for this plan yet");
        }

        List<TestDataRow> rows = new ArrayList<>();
        int testCaseNo = 1;
        for (StudioTestCase testCase : cases) {
            ExportPayload payload = exportPayload(testCase);
            rows.add(toExcelRow(testCaseNo++, testCase, payload.requestBody(), NA, payload.uploadFiles(),
                    payload.contentType()));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        excelWriter.write(output, rows);
        String fileName = exportFileName(request, plan);
        log.info("Prepared {} api-automation rows for plan {} as {}", rows.size(), plan.getName(), fileName);
        return new ExcelExport(fileName, output.toByteArray(), rows.size());
    }

    public String storageType() {
        return properties.getStorage().getType();
    }

    public Map<String, String> runtimeConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("storageType", storageType());
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
            testCase.setStatusCode(request.statusCode() == null ? operation.response().statusCode() : request.statusCode());
            testCase.setResponseType(StringUtils.defaultIfBlank(request.responseType(), operation.response().responseType()));
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
        testCase.setMaxResponseTimeMs(request.maxResponseTimeMs() == null ? 3000L : request.maxResponseTimeMs());
    }

    private SaveTestDataRequest toSaveRequest(String specId, String planId, GeneratedTestCase generated) {
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
                generated.maxResponseTimeMs(),
                generated.contentType()
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
            return new ExportPayload(contentType, inlineRequestBody(testCase), NA);
        }
        String requestBody = inlineJson(RequestFileSupport.stripFileRefs(testCase.getRequestBody()));
        Path dir = Path.of(properties.getDefaultOutputDir()).resolve("files");
        try {
            Files.createDirectories(dir);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create upload files directory: " + dir, exception);
        }
        String prefix = StringUtils.defaultIfBlank(properties.getFilesPathPrefix(), "src/test/data/files")
                .replace('\\', '/')
                .replaceAll("/+$", "");
        List<String> entries = new ArrayList<>();
        for (RequestFileSupport.FilePart part : files) {
            StudioRequestFile stored = studioStorage.findRequestFile(testCase.getId(), part.fileId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Saved file for field '" + part.fieldName() + "' is missing. Re-upload it before export."));
            String outputName = stored.getFileId() + "-" + RequestFileSupport.sanitizeFileName(stored.getFileName());
            try {
                Files.write(dir.resolve(outputName), stored.getContent() == null ? new byte[0] : stored.getContent());
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write upload file " + outputName, exception);
            }
            entries.add(part.fieldName() + "=file:" + prefix + "/" + outputName);
        }
        return new ExportPayload("MULTIPART", requestBody, String.join("|", entries));
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
                ? StringUtils.defaultIfBlank(properties.getDownloadsPathPrefix(), "src/test/data/downloads")
                .replace('\\', '/') + "/" + testCase.getId()
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
                testCase.getMaxResponseTimeMs()
        );
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
        studioStorage.assignRequestFiles(
                testCase.getId(),
                RequestFileSupport.fileIds(testCase.getRequestBody()));
    }

    private String originalFileName(String name) {
        String base = StringUtils.defaultIfBlank(name, "upload.bin").replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        return StringUtils.defaultIfBlank(base, "upload.bin");
    }

    private record ExportPayload(String contentType, String requestBody, String uploadFiles) {
    }
}
