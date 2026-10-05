package com.tx.api.test.generator.studio.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class StudioTestCase {

    private String id;
    private String specId;
    private String planId;
    private String operationKey;
    private String testName;
    private String method;
    private String rawPath;
    private String endpoint;
    private String baseUrl;
    private String contentType;
    private Map<String, String> pathParams = new LinkedHashMap<>();
    private Map<String, String> queryParams = new LinkedHashMap<>();
    private Map<String, String> headers = new LinkedHashMap<>();
    private Map<String, String> cookieParams = new LinkedHashMap<>();
    private Object requestBody;
    private Map<String, Object> responseJsonSchema;
    private int statusCode;
    private String responseType;
    private String expectedResponse;
    private String enabled;
    private String testGroup;
    private String jsonPathAssertions;
    private String extracts;
    private long maxResponseTimeMs;
    private java.util.List<String> environments;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdByAccountId;
    private String createdByLabel;
    private String updatedByAccountId;
    private String updatedByLabel;
    private Instant expectedUpdatedAt;
    private boolean forceOverwrite;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSpecId() {
        return specId;
    }

    public void setSpecId(String specId) {
        this.specId = specId;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getOperationKey() {
        return operationKey;
    }

    public void setOperationKey(String operationKey) {
        this.operationKey = operationKey;
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getRawPath() {
        return rawPath;
    }

    public void setRawPath(String rawPath) {
        this.rawPath = rawPath;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Map<String, String> getPathParams() {
        return pathParams;
    }

    public void setPathParams(Map<String, String> pathParams) {
        this.pathParams = pathParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(pathParams);
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, String> queryParams) {
        this.queryParams = queryParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(queryParams);
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
    }

    public Map<String, String> getCookieParams() {
        return cookieParams;
    }

    public void setCookieParams(Map<String, String> cookieParams) {
        this.cookieParams = cookieParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(cookieParams);
    }

    public Object getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(Object requestBody) {
        this.requestBody = requestBody;
    }

    public Map<String, Object> getResponseJsonSchema() {
        return responseJsonSchema;
    }

    public void setResponseJsonSchema(Map<String, Object> responseJsonSchema) {
        this.responseJsonSchema = responseJsonSchema;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getResponseType() {
        return responseType;
    }

    public void setResponseType(String responseType) {
        this.responseType = responseType;
    }

    public String getExpectedResponse() {
        return expectedResponse;
    }

    public void setExpectedResponse(String expectedResponse) {
        this.expectedResponse = expectedResponse;
    }

    public String getEnabled() {
        return enabled;
    }

    public void setEnabled(String enabled) {
        this.enabled = enabled;
    }

    public String getTestGroup() {
        return testGroup;
    }

    public void setTestGroup(String testGroup) {
        this.testGroup = testGroup;
    }

    public String getJsonPathAssertions() {
        return jsonPathAssertions;
    }

    public void setJsonPathAssertions(String jsonPathAssertions) {
        this.jsonPathAssertions = jsonPathAssertions;
    }

    public String getExtracts() {
        return extracts;
    }

    public void setExtracts(String extracts) {
        this.extracts = extracts;
    }

    public long getMaxResponseTimeMs() {
        return maxResponseTimeMs;
    }

    public void setMaxResponseTimeMs(long maxResponseTimeMs) {
        this.maxResponseTimeMs = maxResponseTimeMs;
    }

    public java.util.List<String> getEnvironments() {
        return environments;
    }

    public void setEnvironments(java.util.List<String> environments) {
        this.environments = environments;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCreatedByAccountId() {
        return createdByAccountId;
    }

    public void setCreatedByAccountId(String createdByAccountId) {
        this.createdByAccountId = createdByAccountId;
    }

    public String getCreatedByLabel() {
        return createdByLabel;
    }

    public void setCreatedByLabel(String createdByLabel) {
        this.createdByLabel = createdByLabel;
    }

    public String getUpdatedByAccountId() {
        return updatedByAccountId;
    }

    public void setUpdatedByAccountId(String updatedByAccountId) {
        this.updatedByAccountId = updatedByAccountId;
    }

    public String getUpdatedByLabel() {
        return updatedByLabel;
    }

    public void setUpdatedByLabel(String updatedByLabel) {
        this.updatedByLabel = updatedByLabel;
    }

    public Instant getExpectedUpdatedAt() {
        return expectedUpdatedAt;
    }

    public void setExpectedUpdatedAt(Instant expectedUpdatedAt) {
        this.expectedUpdatedAt = expectedUpdatedAt;
    }

    public boolean isForceOverwrite() {
        return forceOverwrite;
    }

    public void setForceOverwrite(boolean forceOverwrite) {
        this.forceOverwrite = forceOverwrite;
    }
}
