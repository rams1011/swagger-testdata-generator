package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.tx.api.test.generator.model.TestDataRow;
import com.tx.api.test.generator.studio.config.StudioProperties;
import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.studio.dto.ExcelExport;
import com.tx.api.test.generator.studio.dto.ExportRequest;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;
import com.tx.api.test.generator.studio.dto.SaveTestPlanRequest;
import com.tx.api.test.generator.studio.dto.TestCaseView;
import com.tx.api.test.generator.studio.dto.TestPlanView;
import com.tx.api.test.generator.studio.model.LoadedSpec;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import com.tx.api.test.generator.writer.TestDataExcelWriter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class TestDataStudioService {

    private static final String NA = "NA";
    private static final Gson JSON = new Gson();
    private static final Logger log = LoggerFactory.getLogger(TestDataStudioService.class);

    private final SpecCatalogService specCatalogService;
    private final StudioProperties properties;
    private final StudioStorage studioStorage;
    private final TestDataExcelWriter excelWriter = new TestDataExcelWriter();
    private final String serverPort;

    public TestDataStudioService(SpecCatalogService specCatalogService, StudioProperties properties,
                                 StudioStorage studioStorage, @Value("${server.port:8083}") String serverPort) {
        this.specCatalogService = specCatalogService;
        this.properties = properties;
        this.studioStorage = studioStorage;
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
        studioStorage.saveTestCase(testCase);
        return toView(testCase);
    }

    public TestCaseView update(String id, SaveTestDataRequest request) {
        StudioTestCase testCase = requireCase(id);
        apply(testCase, request, false);
        studioStorage.saveTestCase(testCase);
        return toView(testCase);
    }

    public void delete(String id) {
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
            rows.add(toExcelRow(testCaseNo++, testCase, inlineRequestBody(testCase), NA));
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
        config.put("excelFileName", properties.getDefaultExcelFileName());
        config.put("storageType", storageType());
        if (properties.isH2Storage()) {
            config.put("h2JdbcUrl", properties.getH2().jdbcUrl());
            config.put("h2Username", properties.getH2().getUsername());
            if (properties.getH2().isConsole()) {
                config.put("h2Console", "http://localhost:" + serverPort + "/h2-console");
            }
        }
        return config;
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

        testCase.setSpecId(request.specId());
        testCase.setPlanId(resolvePlanId(request.specId(), request.planId()));
        testCase.setOperationKey(request.operationKey());
        testCase.setTestName(StringUtils.defaultIfBlank(request.testName(), operation.summary()));
        testCase.setMethod(operation.method());
        testCase.setRawPath(operation.rawPath());
        testCase.setEndpoint(buildEndpoint(operation.rawPath(), pathParams));
        testCase.setBaseUrl(StringUtils.defaultIfBlank(request.baseUrl(), spec.getBaseUrl()));
        testCase.setContentType(StringUtils.defaultIfBlank(request.contentType(),
                operation.defaults() == null ? NA : operation.defaults().contentType()));
        testCase.setPathParams(pathParams);
        testCase.setQueryParams(queryParams);
        testCase.setHeaders(headers);
        testCase.setRequestBody(request.requestBody());
        if (operation.response() != null) {
            testCase.setResponseJsonSchema(operation.response().jsonSchema());
            testCase.setStatusCode(request.statusCode() == null ? operation.response().statusCode() : request.statusCode());
            testCase.setResponseType(StringUtils.defaultIfBlank(request.responseType(), operation.response().responseType()));
            testCase.setJsonPathAssertions(StringUtils.defaultIfBlank(request.jsonPathAssertions(),
                    operation.response().jsonPathAssertions()));
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

    private String inlineRequestBody(StudioTestCase testCase) {
        if (testCase.getRequestBody() == null || NA.equalsIgnoreCase(String.valueOf(testCase.getContentType()))) {
            return NA;
        }
        if (testCase.getRequestBody() instanceof String text) {
            return StringUtils.isBlank(text) ? NA : text;
        }
        return JSON.toJson(testCase.getRequestBody());
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

    private TestDataRow toExcelRow(int testCaseNo, StudioTestCase testCase, String payloadReference, String schemaReference) {
        return TestDataRow.builder()
                .testCaseNo(testCaseNo)
                .serviceName(testCase.getTestName())
                .serviceBaseUrl(testCase.getBaseUrl())
                .requestType(testCase.getMethod())
                .endpoint(testCase.getEndpoint())
                .contentType(blankToNa(testCase.getContentType()))
                .queryParamKey(joinKeys(testCase.getQueryParams()))
                .queryParamValue(joinValues(testCase.getQueryParams()))
                .headerName(joinKeys(testCase.getHeaders()))
                .headerValue(joinValues(testCase.getHeaders()))
                .requestBody(payloadReference)
                .statusCode(testCase.getStatusCode())
                .responseType(blankToNa(testCase.getResponseType()))
                .expectedResponse(blankToNa(testCase.getExpectedResponse()))
                .enabled(blankToNa(testCase.getEnabled()))
                .testGroup(blankToNa(testCase.getTestGroup()))
                .jsonPathAssertions(blankToNa(testCase.getJsonPathAssertions()))
                .jsonSchemaPath(schemaReference)
                .maxResponseTimeMs(testCase.getMaxResponseTimeMs())
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

    private String joinKeys(Map<String, String> values) {
        return values == null || values.isEmpty() ? NA : String.join("|", values.keySet());
    }

    private String joinValues(Map<String, String> values) {
        return values == null || values.isEmpty() ? NA : String.join("|", values.values());
    }

    private String blankToNa(String value) {
        return StringUtils.isBlank(value) ? NA : value;
    }
}
