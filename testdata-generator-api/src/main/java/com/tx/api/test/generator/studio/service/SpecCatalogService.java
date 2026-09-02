package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.TestdataGenerator;
import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.catalog.OperationSummary;
import com.tx.api.test.generator.catalog.SpecServer;
import com.tx.api.test.generator.openapi.OpenApiOperationInspector;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.ReloadSpecResult;
import com.tx.api.test.generator.studio.dto.SaveSpecRequest;
import com.tx.api.test.generator.studio.dto.SpecSummary;
import com.tx.api.test.generator.studio.model.LoadedSpec;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SpecCatalogService {

    private final StudioStorage studioStorage;
    private final ConcurrentHashMap<String, LoadedSpec> specs = new ConcurrentHashMap<>();

    public SpecCatalogService(StudioStorage studioStorage) {
        this.studioStorage = studioStorage;
    }

    @PostConstruct
    void ensureUniqueSpecNames() {
        Set<String> used = new HashSet<>();
        for (StoredSpec spec : studioStorage.listSpecs()) {
            String name = uniquifyName(fallbackName(spec.getName(), spec.getTitle(), spec.getSpecId()), used);
            used.add(name.toLowerCase(Locale.ROOT));
            if (!name.equals(spec.getName())) {
                spec.setName(name);
                studioStorage.saveSpec(spec);
            }
        }
        studioStorage.ensureSpecNameUniqueIndex();
    }

    public SpecSummary load(LoadSpecRequest request) {
        String content = resolveContent(request);
        OpenAPI openApi = parseSpec(request, content);
        List<OperationPage> operations = TestdataGenerator.inspect(openApi, request.baseUrl());
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("No operations were found in the Swagger/OpenAPI spec");
        }

        String specId = UUID.randomUUID().toString();
        String name = requireUniqueName(request == null ? null : request.name(), null);
        LoadedSpec loadedSpec = new LoadedSpec(
                specId,
                name,
                openApi,
                operations,
                TestdataGenerator.specTitle(openApi),
                TestdataGenerator.specVersion(openApi),
                TestdataGenerator.specDescription(openApi),
                TestdataGenerator.resolveBaseUrl(openApi, request.baseUrl())
        );
        specs.put(specId, loadedSpec);
        studioStorage.saveSpec(toStoredSpec(loadedSpec, request, content, name));
        return toSummary(loadedSpec, request == null ? null : request.location());
    }

    public SpecSummary rename(String specId, SaveSpecRequest request) {
        StoredSpec stored = studioStorage.findSpec(specId)
                .orElseThrow(() -> new NoSuchElementException("Spec not found: " + specId));
        String name = requireUniqueName(request == null ? null : request.name(), specId);
        stored.setName(name);
        studioStorage.saveSpec(stored);
        LoadedSpec loaded = specs.get(specId);
        if (loaded != null) {
            specs.put(specId, new LoadedSpec(
                    loaded.getSpecId(),
                    name,
                    loaded.getOpenApi(),
                    loaded.getOperations(),
                    loaded.getTitle(),
                    loaded.getVersion(),
                    loaded.getDescription(),
                    loaded.getBaseUrl()
            ));
            return toSummary(specs.get(specId), stored.getSourceLocation());
        }
        return toSummary(stored);
    }

    public void delete(String specId) {
        studioStorage.findSpec(specId)
                .orElseThrow(() -> new NoSuchElementException("Spec not found: " + specId));
        studioStorage.deleteSpec(specId);
        specs.remove(specId);
    }

    public ReloadSpecResult reload(String specId, LoadSpecRequest request) {
        StoredSpec existing = studioStorage.findSpec(specId)
                .orElseThrow(() -> new NoSuchElementException("Spec not found: " + specId));
        Set<String> previousKeys = previousOperationKeys(specId, existing);

        String location = firstNonBlank(request == null ? null : request.location(), existing.getSourceLocation());
        String content = resolveReloadContent(request, existing, location);
        OpenAPI openApi = parseSpec(new LoadSpecRequest(location, content, null, null, null), content);
        String baseUrl = firstNonBlank(request == null ? null : request.baseUrl(), existing.getBaseUrl());
        List<OperationPage> operations = TestdataGenerator.inspect(openApi, baseUrl);
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("No operations were found in the updated Swagger/OpenAPI spec");
        }
        if (StringUtils.isBlank(baseUrl)) {
            baseUrl = TestdataGenerator.resolveBaseUrl(openApi, null);
        }
        String name = existing.getName();
        if (request != null && StringUtils.isNotBlank(request.name())
                && !request.name().trim().equalsIgnoreCase(StringUtils.defaultString(name))) {
            name = requireUniqueName(request.name(), specId);
        } else {
            name = fallbackName(name, TestdataGenerator.specTitle(openApi), specId);
        }

        LoadedSpec loadedSpec = new LoadedSpec(
                specId,
                name,
                openApi,
                operations,
                TestdataGenerator.specTitle(openApi),
                TestdataGenerator.specVersion(openApi),
                TestdataGenerator.specDescription(openApi),
                baseUrl
        );
        specs.put(specId, loadedSpec);
        studioStorage.saveSpec(toStoredSpec(loadedSpec,
                new LoadSpecRequest(location, content, request == null ? null : request.fileName(), baseUrl, name),
                content, name));

        Set<String> nextKeys = new LinkedHashSet<>();
        operations.forEach(operation -> nextKeys.add(operation.key()));
        List<String> added = nextKeys.stream().filter(key -> !previousKeys.contains(key)).toList();
        List<String> removed = previousKeys.stream().filter(key -> !nextKeys.contains(key)).toList();
        int unchanged = (int) nextKeys.stream().filter(previousKeys::contains).count();
        int orphaned = (int) studioStorage.listTestCases(specId, null, null).stream()
                .filter(testCase -> !nextKeys.contains(testCase.getOperationKey()))
                .count();
        return new ReloadSpecResult(toSummary(loadedSpec, location), added, removed, unchanged, orphaned);
    }

    public List<SpecSummary> list() {
        Map<String, SpecSummary> summaries = new LinkedHashMap<>();
        studioStorage.listSpecs().forEach(stored -> summaries.put(stored.getSpecId(), toSummary(stored)));
        specs.values().forEach(loaded -> summaries.put(loaded.getSpecId(), toSummary(loaded, sourceLocation(loaded.getSpecId()))));
        return summaries.values().stream()
                .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(
                        StringUtils.defaultString(left.name(), left.title()),
                        StringUtils.defaultString(right.name(), right.title())))
                .toList();
    }

    public SpecSummary getSummary(String specId) {
        LoadedSpec loaded = specs.get(specId);
        if (loaded != null) {
            return toSummary(loaded, sourceLocation(specId));
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
                fallbackName(stored.getName(), stored.getTitle(), stored.getSpecId()),
                openApi,
                operations,
                stored.getTitle(),
                stored.getVersion(),
                stored.getDescription(),
                stored.getBaseUrl()
        );
    }

    private StoredSpec toStoredSpec(LoadedSpec loaded, LoadSpecRequest request, String content, String name) {
        StoredSpec stored = new StoredSpec();
        stored.setSpecId(loaded.getSpecId());
        stored.setName(name);
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

    private SpecSummary toSummary(LoadedSpec spec, String sourceLocation) {
        List<String> tags = spec.getOperations().stream()
                .map(OperationPage::tag)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        List<SpecServer> servers = spec.getOperations().isEmpty() || spec.getOperations().get(0).servers() == null
                ? List.of()
                : spec.getOperations().get(0).servers();
        return new SpecSummary(
                spec.getSpecId(),
                spec.getName(),
                spec.getTitle(),
                spec.getVersion(),
                spec.getDescription(),
                spec.getBaseUrl(),
                spec.getOperations().size(),
                tags,
                sourceLocation,
                servers
        );
    }

    private SpecSummary toSummary(LoadedSpec spec) {
        return toSummary(spec, sourceLocation(spec.getSpecId()));
    }

    private SpecSummary toSummary(StoredSpec spec) {
        return new SpecSummary(
                spec.getSpecId(),
                spec.getName(),
                spec.getTitle(),
                spec.getVersion(),
                spec.getDescription(),
                spec.getBaseUrl(),
                spec.getOperationCount(),
                List.of(),
                spec.getSourceLocation(),
                List.of()
        );
    }

    private Set<String> previousOperationKeys(String specId, StoredSpec existing) {
        LoadedSpec loaded = specs.get(specId);
        if (loaded == null) {
            try {
                loaded = restore(existing);
            } catch (RuntimeException ignored) {
                return Set.of();
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        loaded.getOperations().forEach(operation -> keys.add(operation.key()));
        return keys;
    }

    private String resolveReloadContent(LoadSpecRequest request, StoredSpec existing, String location) {
        if (request != null && StringUtils.isNotBlank(request.content())) {
            return request.content();
        }
        if (StringUtils.isNotBlank(location)) {
            return TestdataGenerator.readSpecText(location);
        }
        throw new IllegalArgumentException(
                "This spec has no URL or file path to re-fetch. Upload a file or paste the updated OpenAPI content.");
    }

    private String sourceLocation(String specId) {
        return studioStorage.findSpec(specId).map(StoredSpec::getSourceLocation).orElse(null);
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.isNotBlank(primary) ? primary.trim() : StringUtils.trimToNull(fallback);
    }

    private String requireUniqueName(String name, String excludeSpecId) {
        String trimmed = StringUtils.trimToNull(name);
        if (trimmed == null) {
            throw new IllegalArgumentException("Spec name is required and must be unique");
        }
        boolean duplicate = studioStorage.listSpecs().stream()
                .anyMatch(spec -> !spec.getSpecId().equals(excludeSpecId)
                        && trimmed.equalsIgnoreCase(StringUtils.trimToEmpty(spec.getName())));
        if (duplicate) {
            throw new IllegalArgumentException("A spec named '" + trimmed + "' already exists");
        }
        return trimmed;
    }

    private String fallbackName(String name, String title, String specId) {
        if (StringUtils.isNotBlank(name)) {
            return name.trim();
        }
        if (StringUtils.isNotBlank(title)) {
            return title.trim();
        }
        return specId;
    }

    private String uniquifyName(String desired, Set<String> used) {
        String base = StringUtils.defaultIfBlank(desired, "spec").trim();
        String candidate = base;
        int suffix = 2;
        while (used.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
