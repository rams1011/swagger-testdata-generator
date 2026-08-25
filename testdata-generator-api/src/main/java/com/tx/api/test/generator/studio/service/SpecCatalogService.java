package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.TestdataGenerator;
import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.catalog.OperationSummary;
import com.tx.api.test.generator.openapi.OpenApiOperationInspector;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.SpecSummary;
import com.tx.api.test.generator.studio.model.LoadedSpec;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SpecCatalogService {

    private final StudioStorage studioStorage;
    private final ConcurrentHashMap<String, LoadedSpec> specs = new ConcurrentHashMap<>();

    public SpecCatalogService(StudioStorage studioStorage) {
        this.studioStorage = studioStorage;
    }

    public SpecSummary load(LoadSpecRequest request) {
        String content = resolveContent(request);
        OpenAPI openApi = parseSpec(request, content);
        List<OperationPage> operations = TestdataGenerator.inspect(openApi, request.baseUrl());
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("No operations were found in the Swagger/OpenAPI spec");
        }

        String specId = UUID.randomUUID().toString();
        LoadedSpec loadedSpec = new LoadedSpec(
                specId,
                openApi,
                operations,
                TestdataGenerator.specTitle(openApi),
                TestdataGenerator.specVersion(openApi),
                TestdataGenerator.specDescription(openApi),
                TestdataGenerator.resolveBaseUrl(openApi, request.baseUrl())
        );
        specs.put(specId, loadedSpec);
        studioStorage.saveSpec(toStoredSpec(loadedSpec, request, content));
        return toSummary(loadedSpec);
    }

    public List<SpecSummary> list() {
        Map<String, SpecSummary> summaries = new LinkedHashMap<>();
        studioStorage.listSpecs().forEach(stored -> summaries.put(stored.getSpecId(), toSummary(stored)));
        specs.values().forEach(loaded -> summaries.put(loaded.getSpecId(), toSummary(loaded)));
        return List.copyOf(summaries.values());
    }

    public SpecSummary getSummary(String specId) {
        LoadedSpec loaded = specs.get(specId);
        if (loaded != null) {
            return toSummary(loaded);
        }
        return studioStorage.findSpec(specId)
                .map(this::toSummary)
                .orElseThrow(() -> new NoSuchElementException("Spec not found: " + specId));
    }

    public LoadedSpec requireSpec(String specId) {
        LoadedSpec loaded = specs.get(specId);
        if (loaded != null) {
            return loaded;
        }
        StoredSpec stored = studioStorage.findSpec(specId)
                .orElseThrow(() -> new NoSuchElementException("Spec not found: " + specId));
        LoadedSpec restored = restore(stored);
        specs.put(specId, restored);
        return restored;
    }

    public List<OperationSummary> listOperations(String specId) {
        return requireSpec(specId).getOperations().stream()
                .map(OpenApiOperationInspector::toSummary)
                .toList();
    }

    public OperationPage getOperation(String specId, String operationKey) {
        OperationPage page = requireSpec(specId).getOperation(operationKey);
        if (page == null) {
            throw new NoSuchElementException("Operation not found: " + operationKey);
        }
        return page;
    }

    private LoadedSpec restore(StoredSpec stored) {
        OpenAPI openApi;
        if (StringUtils.isNotBlank(stored.getSourceContent())) {
            openApi = TestdataGenerator.loadSpecFromContent(stored.getSourceContent());
        } else if (StringUtils.isNotBlank(stored.getSourceLocation())) {
            openApi = TestdataGenerator.loadSpec(stored.getSourceLocation());
        } else {
            throw new IllegalStateException("Stored spec is missing OpenAPI source: " + stored.getSpecId());
        }
        List<OperationPage> operations = TestdataGenerator.inspect(openApi, stored.getBaseUrl());
        return new LoadedSpec(
                stored.getSpecId(),
                openApi,
                operations,
                stored.getTitle(),
                stored.getVersion(),
                stored.getDescription(),
                stored.getBaseUrl()
        );
    }

    private StoredSpec toStoredSpec(LoadedSpec loaded, LoadSpecRequest request, String content) {
        StoredSpec stored = new StoredSpec();
        stored.setSpecId(loaded.getSpecId());
        stored.setTitle(loaded.getTitle());
        stored.setVersion(loaded.getVersion());
        stored.setDescription(loaded.getDescription());
        stored.setBaseUrl(loaded.getBaseUrl());
        stored.setSourceLocation(request == null ? null : request.location());
        stored.setSourceContent(content);
        stored.setOperationCount(loaded.getOperations().size());
        return stored;
    }

    private OpenAPI parseSpec(LoadSpecRequest request, String content) {
        if (StringUtils.isNotBlank(content)) {
            return TestdataGenerator.loadSpecFromContent(content);
        }
        if (request != null && StringUtils.isNotBlank(request.location())) {
            return TestdataGenerator.loadSpec(request.location());
        }
        throw new IllegalArgumentException("Provide a Swagger URL, file path, or spec content");
    }

    private String resolveContent(LoadSpecRequest request) {
        if (request != null && StringUtils.isNotBlank(request.content())) {
            return request.content();
        }
        if (request != null && StringUtils.isNotBlank(request.location())) {
            return TestdataGenerator.readSpecText(request.location());
        }
        return null;
    }

    private SpecSummary toSummary(LoadedSpec spec) {
        List<String> tags = spec.getOperations().stream()
                .map(OperationPage::tag)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        return new SpecSummary(
                spec.getSpecId(),
                spec.getTitle(),
                spec.getVersion(),
                spec.getDescription(),
                spec.getBaseUrl(),
                spec.getOperations().size(),
                tags
        );
    }

    private SpecSummary toSummary(StoredSpec spec) {
        return new SpecSummary(
                spec.getSpecId(),
                spec.getTitle(),
                spec.getVersion(),
                spec.getDescription(),
                spec.getBaseUrl(),
                spec.getOperationCount(),
                List.of()
        );
    }
}
