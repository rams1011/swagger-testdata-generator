package com.tx.api.test.generator.model;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TestDataRow {
    private final int testCaseNo;
    private final String serviceName;
    private final String serviceBaseUrl;
    private final String requestType;
    private final String endpoint;
    private final String contentType;
    private final String queryParamKey;
    private final String queryParamValue;
    private final String headerName;
    private final String headerValue;
    private final String requestBody;
    private final int statusCode;
    private final String responseType;
    private final String expectedResponse;
    private final String enabled;
    private final String testGroup;
    private final String jsonPathAssertions;
    private final String jsonSchemaPath;
    private final long maxResponseTimeMs;
    private final String uploadFiles;
    private final String downloadFilePath;
    private final String extracts;
}
