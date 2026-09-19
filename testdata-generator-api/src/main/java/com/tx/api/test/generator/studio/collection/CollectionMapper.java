package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.model.StudioTestPlan;
import com.tx.api.test.generator.studio.service.CaseVariables;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CollectionMapper {

    private CollectionMapper() {
    }

    public static StudioCollection fromPlan(StoredSpec spec, StudioTestPlan plan, List<StudioTestCase> cases) {
        return fromPlan(spec, plan, cases, Map.of());
    }

    public static StudioCollection fromPlan(StoredSpec spec, StudioTestPlan plan, List<StudioTestCase> cases,
                                            Map<String, List<StudioRequestFile>> filesByCaseId) {
        if (spec == null) {
            throw new CollectionFormatException("spec is required");
        }
        if (plan == null) {
            throw new CollectionFormatException("plan is required");
        }
        Map<String, List<StudioRequestFile>> files = filesByCaseId == null ? Map.of() : filesByCaseId;
        List<StudioCollectionCase> mapped = new ArrayList<>();
        for (StudioTestCase testCase : CaseVariables.orderForChain(cases)) {
            mapped.add(toCollectionCase(testCase, files.get(testCase.getId()), spec.getBaseUrl()));
        }
        return new StudioCollection(
                CollectionFormat.COLLECTION_FORMAT,
                CollectionFormat.VERSION,
                toCollectionSpec(spec),
                toCollectionPlan(plan),
                List.copyOf(mapped)
        );
    }

    public static CollectionSnapshot toSnapshot(StudioCollection collection) {
        validate(collection);
        StoredSpec spec = toStoredSpec(collection.spec());
        StudioTestPlan plan = toStoredPlan(collection.plan(), spec.getSpecId());
        List<StudioTestCase> cases = new ArrayList<>();
        for (StudioCollectionCase item : collection.cases()) {
            cases.add(toTestCase(item, spec.getSpecId(), plan.getId(), spec.getBaseUrl()));
        }
        return new CollectionSnapshot(spec, plan, List.copyOf(cases));
    }

    public static StudioCollection readFrom(StudioStorage storage, String specId, String planId) {
        StoredSpec spec = storage.findSpec(specId)
                .orElseThrow(() -> new CollectionFormatException("Spec not found: " + specId));
        StudioTestPlan plan = storage.findPlan(planId)
                .orElseThrow(() -> new CollectionFormatException("Plan not found: " + planId));
        List<StudioTestCase> cases = storage.listTestCases(specId, planId, null);
        Map<String, List<StudioRequestFile>> filesByCaseId = new LinkedHashMap<>();
        for (StudioTestCase testCase : cases) {
            List<StudioRequestFile> files = storage.listRequestFiles(testCase.getId());
            if (!files.isEmpty()) {
                filesByCaseId.put(testCase.getId(), files);
            }
        }
        return fromPlan(spec, plan, cases, filesByCaseId);
    }

    public static void writeTo(StudioStorage storage, StudioCollection collection) {
        CollectionSnapshot snapshot = toSnapshot(collection);
        storage.saveSpec(snapshot.spec());
        storage.savePlan(snapshot.plan());
        for (StudioTestCase testCase : snapshot.cases()) {
            storage.saveTestCase(testCase);
        }
    }

    public static void validate(StudioCollection collection) {
        if (collection == null) {
            throw new CollectionFormatException("Collection is required");
        }
        if (!CollectionFormat.COLLECTION_FORMAT.equals(collection.format())) {
            throw new CollectionFormatException("Unsupported collection format: " + collection.format());
        }
        if (collection.version() != CollectionFormat.VERSION) {
            throw new CollectionFormatException("Unsupported collection version: " + collection.version());
        }
        if (collection.spec() == null || StringUtils.isBlank(collection.spec().id())
                || StringUtils.isBlank(collection.spec().name())) {
            throw new CollectionFormatException("Collection spec.id and spec.name are required");
        }
        if (collection.plan() == null || StringUtils.isBlank(collection.plan().id())
                || StringUtils.isBlank(collection.plan().name())) {
            throw new CollectionFormatException("Collection plan.id and plan.name are required");
        }
        if (collection.cases() == null) {
            throw new CollectionFormatException("Collection cases array is required");
        }
        for (StudioCollectionCase item : collection.cases()) {
            validateCase(item);
        }
    }

    public static void validate(StudioEnvironment environment) {
        if (environment == null) {
            throw new CollectionFormatException("Environment is required");
        }
        if (!CollectionFormat.ENVIRONMENT_FORMAT.equals(environment.format())) {
            throw new CollectionFormatException("Unsupported environment format: " + environment.format());
        }
        if (environment.version() != CollectionFormat.VERSION) {
            throw new CollectionFormatException("Unsupported environment version: " + environment.version());
        }
        if (StringUtils.isBlank(environment.name())) {
            throw new CollectionFormatException("Environment name is required");
        }
    }

    private static void validateCase(StudioCollectionCase item) {
        if (item == null || StringUtils.isBlank(item.id()) || StringUtils.isBlank(item.testName())
                || StringUtils.isBlank(item.method()) || StringUtils.isBlank(item.path())) {
            throw new CollectionFormatException("Each case needs id, testName, method, and path");
        }
    }

    private static StudioCollectionSpec toCollectionSpec(StoredSpec spec) {
        return new StudioCollectionSpec(
                spec.getSpecId(),
                spec.getName(),
                spec.getLabel(),
                spec.getTitle(),
                spec.getVersion(),
                spec.getDescription(),
                spec.getBaseUrl()
        );
    }

    private static StudioCollectionPlan toCollectionPlan(StudioTestPlan plan) {
        return new StudioCollectionPlan(plan.getId(), plan.getName(), plan.getDescription());
    }

    public static String resolveStoredBaseUrl(String caseBaseUrl, String collectionSpecBaseUrl,
                                              String targetSpecBaseUrl) {
        if (!CollectionFormat.sharesSpecBaseUrl(caseBaseUrl, collectionSpecBaseUrl)) {
            return caseBaseUrl;
        }
        if (StringUtils.isNotBlank(targetSpecBaseUrl)) {
            return targetSpecBaseUrl.trim();
        }
        if (StringUtils.isNotBlank(collectionSpecBaseUrl)) {
            return collectionSpecBaseUrl.trim();
        }
        return null;
    }

    private static StudioCollectionCase toCollectionCase(StudioTestCase testCase, List<StudioRequestFile> files,
                                                         String specBaseUrl) {
        return new StudioCollectionCase(
                testCase.getId(),
                testCase.getOperationKey(),
                testCase.getTestName(),
                testCase.getMethod(),
                testCase.getRawPath(),
                testCase.getEndpoint(),
                CollectionFormat.sharesSpecBaseUrl(testCase.getBaseUrl(), specBaseUrl)
                        ? CollectionFormat.BASE_URL_PLACEHOLDER
                        : testCase.getBaseUrl(),
                testCase.getContentType(),
                copy(testCase.getPathParams()),
                copy(testCase.getQueryParams()),
                copy(testCase.getHeaders()),
                copy(testCase.getCookieParams()),
                testCase.getRequestBody(),
                emptyToNull(testCase.getResponseJsonSchema()),
                testCase.getStatusCode(),
                testCase.getResponseType(),
                testCase.getExpectedResponse(),
                testCase.getEnabled(),
                testCase.getTestGroup(),
                testCase.getJsonPathAssertions(),
                testCase.getExtracts(),
                testCase.getMaxResponseTimeMs(),
                toCollectionFiles(testCase.getId(), files)
        );
    }

    private static List<StudioCollectionFile> toCollectionFiles(String caseId, List<StudioRequestFile> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        List<StudioCollectionFile> mapped = new ArrayList<>();
        for (StudioRequestFile file : files) {
            mapped.add(new StudioCollectionFile(
                    file.getFileId(),
                    file.getFileName(),
                    file.getContentType(),
                    file.getSizeBytes(),
                    CollectionFormat.relativeFilePath(caseId, file.getFileId(), file.getFileName())
            ));
        }
        return List.copyOf(mapped);
    }

    private static StoredSpec toStoredSpec(StudioCollectionSpec spec) {
        StoredSpec stored = new StoredSpec();
        stored.setSpecId(spec.id());
        stored.setName(spec.name());
        stored.setLabel(spec.label());
        stored.setTitle(StringUtils.defaultIfBlank(spec.title(), spec.name()));
        stored.setVersion(spec.version());
        stored.setDescription(spec.description());
        stored.setBaseUrl(spec.baseUrl());
        return stored;
    }

    private static StudioTestPlan toStoredPlan(StudioCollectionPlan plan, String specId) {
        StudioTestPlan stored = new StudioTestPlan();
        stored.setId(plan.id());
        stored.setSpecId(specId);
        stored.setName(plan.name());
        stored.setDescription(plan.description());
        return stored;
    }

    private static StudioTestCase toTestCase(StudioCollectionCase item, String specId, String planId,
                                             String specBaseUrl) {
        StudioTestCase testCase = new StudioTestCase();
        testCase.setId(item.id());
        testCase.setSpecId(specId);
        testCase.setPlanId(planId);
        testCase.setOperationKey(item.operationKey());
        testCase.setTestName(item.testName());
        testCase.setMethod(item.method());
        testCase.setRawPath(item.path());
        testCase.setEndpoint(StringUtils.defaultIfBlank(item.endpoint(), item.path()));
        testCase.setBaseUrl(resolveStoredBaseUrl(item.baseUrl(), specBaseUrl, specBaseUrl));
        testCase.setContentType(item.contentType());
        testCase.setPathParams(copy(item.pathParams()));
        testCase.setQueryParams(copy(item.queryParams()));
        testCase.setHeaders(copy(item.headers()));
        testCase.setCookieParams(copy(item.cookies()));
        testCase.setRequestBody(item.body());
        testCase.setResponseJsonSchema(item.responseJsonSchema());
        testCase.setStatusCode(item.statusCode());
        testCase.setResponseType(item.responseType());
        testCase.setExpectedResponse(item.expectedResponse());
        testCase.setEnabled(StringUtils.defaultIfBlank(item.enabled(), "Y"));
        testCase.setTestGroup(item.testGroup());
        testCase.setJsonPathAssertions(item.jsonPathAssertions());
        testCase.setExtracts(item.extracts());
        testCase.setMaxResponseTimeMs(item.maxResponseTimeMs() == null ? 0 : item.maxResponseTimeMs());
        return testCase;
    }

    private static Map<String, String> copy(Map<String, String> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static Map<String, Object> emptyToNull(Map<String, Object> source) {
        return source == null || source.isEmpty() ? null : source;
    }
}
