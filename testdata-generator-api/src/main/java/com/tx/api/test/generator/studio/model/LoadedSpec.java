package com.tx.api.test.generator.studio.model;

import com.tx.api.test.generator.catalog.OperationPage;
import io.swagger.v3.oas.models.OpenAPI;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class LoadedSpec {

    private final String specId;
    private final OpenAPI openApi;
    private final List<OperationPage> operations;
    private final Map<String, OperationPage> operationsByKey;
    private final String title;
    private final String version;
    private final String description;
    private final String baseUrl;

    public LoadedSpec(String specId, OpenAPI openApi, List<OperationPage> operations,
                      String title, String version, String description, String baseUrl) {
        this.specId = specId;
        this.openApi = openApi;
        this.operations = List.copyOf(operations);
        this.operationsByKey = operations.stream()
                .collect(Collectors.toMap(OperationPage::key, Function.identity(), (left, right) -> left));
        this.title = title;
        this.version = version;
        this.description = description;
        this.baseUrl = baseUrl;
    }

    public String getSpecId() {
        return specId;
    }

    public OpenAPI getOpenApi() {
        return openApi;
    }

    public List<OperationPage> getOperations() {
        return operations;
    }

    public OperationPage getOperation(String key) {
        return operationsByKey.get(key);
    }

    public String getTitle() {
        return title;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
