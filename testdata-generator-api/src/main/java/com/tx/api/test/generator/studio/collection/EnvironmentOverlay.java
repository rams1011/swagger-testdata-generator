package com.tx.api.test.generator.studio.collection;

import com.tx.api.test.generator.studio.model.StudioTestCase;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EnvironmentOverlay {

    private EnvironmentOverlay() {
    }

    public static StudioTestCase merge(StudioTestCase base, StudioEnvironmentCaseOverlay overlay) {
        if (base == null || overlay == null) {
            return base;
        }
        StudioTestCase merged = copy(base);
        if (overlay.body() != null) {
            merged.setRequestBody(overlay.body());
        }
        if (overlay.expectedResponse() != null) {
            merged.setExpectedResponse(overlay.expectedResponse());
        }
        if (overlay.pathParams() != null) {
            merged.setPathParams(copyMap(overlay.pathParams()));
            if (StringUtils.isNotBlank(merged.getRawPath())) {
                merged.setEndpoint(endpoint(merged.getRawPath(), merged.getPathParams()));
            }
        }
        if (overlay.queryParams() != null) {
            merged.setQueryParams(copyMap(overlay.queryParams()));
        }
        if (overlay.headers() != null) {
            merged.setHeaders(copyMap(overlay.headers()));
        }
        if (overlay.cookies() != null) {
            merged.setCookieParams(copyMap(overlay.cookies()));
        }
        if (overlay.statusCode() != null) {
            merged.setStatusCode(overlay.statusCode());
        }
        if (overlay.jsonPathAssertions() != null) {
            merged.setJsonPathAssertions(overlay.jsonPathAssertions());
        }
        return merged;
    }

    public static StudioEnvironmentCaseOverlay fromCase(StudioTestCase testCase) {
        if (testCase == null) {
            return null;
        }
        return new StudioEnvironmentCaseOverlay(
                testCase.getRequestBody(),
                testCase.getExpectedResponse(),
                copyMap(testCase.getPathParams()),
                copyMap(testCase.getQueryParams()),
                copyMap(testCase.getHeaders()),
                copyMap(testCase.getCookieParams()),
                testCase.getStatusCode(),
                testCase.getJsonPathAssertions()
        );
    }

    public static StudioEnvironment withCase(StudioEnvironment environment, String caseId,
                                             StudioEnvironmentCaseOverlay overlay) {
        if (environment == null || StringUtils.isBlank(caseId) || overlay == null) {
            return environment;
        }
        Map<String, StudioEnvironmentCaseOverlay> cases = new LinkedHashMap<>(environment.caseOverlays());
        cases.put(caseId.trim(), overlay);
        return new StudioEnvironment(
                environment.format(),
                environment.version(),
                environment.name(),
                environment.values(),
                environment.auth(),
                cases
        );
    }

    public static StudioEnvironment withoutCase(StudioEnvironment environment, String caseId) {
        if (environment == null || StringUtils.isBlank(caseId) || !environment.caseOverlays().containsKey(caseId)) {
            return environment;
        }
        Map<String, StudioEnvironmentCaseOverlay> cases = new LinkedHashMap<>(environment.caseOverlays());
        cases.remove(caseId);
        return new StudioEnvironment(
                environment.format(),
                environment.version(),
                environment.name(),
                environment.values(),
                environment.auth(),
                cases
        );
    }

    public static boolean hasOverlay(StudioEnvironment environment, String caseId) {
        return environment != null && StringUtils.isNotBlank(caseId) && environment.caseOverlays().containsKey(caseId);
    }

    public static String endpoint(String rawPath, Map<String, String> pathParams) {
        String endpoint = StringUtils.defaultString(rawPath);
        if (pathParams == null) {
            return endpoint;
        }
        for (Map.Entry<String, String> entry : pathParams.entrySet()) {
            if (StringUtils.isBlank(entry.getKey())) {
                continue;
            }
            endpoint = endpoint.replace("{" + entry.getKey() + "}", StringUtils.defaultString(entry.getValue()));
        }
        return endpoint;
    }

    public static StudioTestCase copy(StudioTestCase source) {
        StudioTestCase copy = new StudioTestCase();
        copy.setId(source.getId());
        copy.setSpecId(source.getSpecId());
        copy.setPlanId(source.getPlanId());
        copy.setOperationKey(source.getOperationKey());
        copy.setTestName(source.getTestName());
        copy.setMethod(source.getMethod());
        copy.setRawPath(source.getRawPath());
        copy.setEndpoint(source.getEndpoint());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setContentType(source.getContentType());
        copy.setPathParams(copyMap(source.getPathParams()));
        copy.setQueryParams(copyMap(source.getQueryParams()));
        copy.setHeaders(copyMap(source.getHeaders()));
        copy.setCookieParams(copyMap(source.getCookieParams()));
        copy.setRequestBody(source.getRequestBody());
        copy.setResponseJsonSchema(source.getResponseJsonSchema());
        copy.setStatusCode(source.getStatusCode());
        copy.setResponseType(source.getResponseType());
        copy.setExpectedResponse(source.getExpectedResponse());
        copy.setEnabled(source.getEnabled());
        copy.setLifecycle(source.getLifecycle());
        copy.setTestGroup(source.getTestGroup());
        copy.setJsonPathAssertions(source.getJsonPathAssertions());
        copy.setExtracts(source.getExtracts());
        copy.setMaxResponseTimeMs(source.getMaxResponseTimeMs());
        copy.setEnvironments(source.getEnvironments() == null ? null : new java.util.ArrayList<>(source.getEnvironments()));
        copyAuditFields(source, copy);
        return copy;
    }

    private static void copyAuditFields(StudioTestCase source, StudioTestCase copy) {
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setCreatedByAccountId(source.getCreatedByAccountId());
        copy.setCreatedByLabel(source.getCreatedByLabel());
        copy.setUpdatedByAccountId(source.getUpdatedByAccountId());
        copy.setUpdatedByLabel(source.getUpdatedByLabel());
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
