package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.TestdataGenerator;
import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.catalog.OperationSummary;
import com.tx.api.test.generator.catalog.SpecServer;
import com.tx.api.test.generator.openapi.OpenApiOperationInspector;
import com.tx.api.test.generator.studio.collection.EnvironmentScope;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.ManualOperationRequest;
import com.tx.api.test.generator.studio.dto.ReloadSpecResult;
import com.tx.api.test.generator.studio.dto.SaveSpecRequest;
import com.tx.api.test.generator.studio.dto.SpecSummary;
import com.tx.api.test.generator.studio.model.LoadedSpec;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.model.StudioTestCase;
import com.tx.api.test.generator.studio.openapi.ManualSpecDocument;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import com.tx.api.test.generator.studio.tenant.TenantContext;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
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
@DependsOn("tenantBootstrap")
public class SpecCatalogService {

    private final StudioStorage studioStorage;
    private final ConcurrentHashMap<String, LoadedSpec> specs = new ConcurrentHashMap<>();

    public SpecCatalogService(StudioStorage studioStorage) {
        this.studioStorage = studioStorage;
    }

    @PostConstruct
    void ensureUniqueSpecNames() {
        TenantContext.runAs(TenantContext.LOCAL_TENANT_ID, () -> {
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
        });
    }

    public SpecSummary load(LoadSpecRequest request) {
        if (request != null && Boolean.TRUE.equals(request.manual())) {
            return createManual(request);
        }
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
        return toSummary(loadedSpec, request == null ? null : request.location(),
                request == null ? null : request.label());
    }

    public SpecSummary createManual(LoadSpecRequest request) {
        String name = requireUniqueName(request == null ? null : request.name(), null);
        String baseUrl = firstNonBlank(request == null ? null : request.baseUrl(), "http://localhost:8080");
        String content = ManualSpecDocument.emptyCatalog(name, baseUrl);
        OpenAPI openApi = TestdataGenerator.loadSpecFromContent(content);
        String specId = UUID.randomUUID().toString();
        LoadedSpec loadedSpec = new LoadedSpec(
                specId,
                name,
                openApi,
                List.of(),
                TestdataGenerator.specTitle(openApi),
                TestdataGenerator.specVersion(openApi),
                TestdataGenerator.specDescription(openApi),
                TestdataGenerator.resolveBaseUrl(openApi, baseUrl)
        );
        specs.put(specId, loadedSpec);
        studioStorage.saveSpec(toStoredSpec(loadedSpec,
                new LoadSpecRequest(null, content, null, baseUrl, name,
                        request == null ? null : request.label(), true),
                content, name));
        return toSummary(loadedSpec, null, request == null ? null : request.label());
    }

    public OperationPage addOperation(String specId, ManualOperationRequest request) {
        StoredSpec stored = requireStoredSpec(specId);
        String nextContent = ManualSpecDocument.addOperation(jsonSource(stored.getSourceContent()), request);
        return persistManualContent(
                stored,
                nextContent,
                ManualSpecDocument.normalizePath(request == null ? null : request.path()),
                request == null ? null : request.method());
    }

    public void deleteOperation(String specId, String operationKey) {
        StoredSpec stored = requireStoredSpec(specId);
        OperationPage page = getOperation(specId, operationKey);
        String nextContent = ManualSpecDocument.removeOperation(
                jsonSource(stored.getSourceContent()), page.method(), page.path());
        persistManualContent(stored, nextContent, null, null);
    }

    public SpecSummary rename(String specId, SaveSpecRequest request) {
        StoredSpec stored = studioStorage.findSpec(specId)
                .orElseThrow(() -> new NoSuchElementException("Spec not found: " + specId));
        boolean rename = request != null && StringUtils.isNotBlank(request.name());
        boolean updateLabel = request != null && request.label() != null;
        boolean updateBaseUrl = request != null && request.baseUrl() != null;
        if (!rename && !updateLabel && !updateBaseUrl) {
            throw new IllegalArgumentException("Name, label, or base URL is required");
        }
        if (rename) {
            stored.setName(requireUniqueName(request.name(), specId));
        }
        if (updateLabel) {
            stored.setLabel(normalizeLabel(request.label()));
        }
        String previousBaseUrl = stored.getBaseUrl();
        if (updateBaseUrl) {
            stored.setBaseUrl(resolveUpdatedBaseUrl(stored, request.baseUrl()));
        }
        studioStorage.saveSpec(stored);
        refreshLoaded(stored, updateBaseUrl);
        if (updateBaseUrl) {
            propagateCaseBaseUrls(specId, previousBaseUrl, stored.getBaseUrl());
        }
        return getSummary(specId);
    }

    public void renameEnvironmentReferences(String specId, String from, String to) {
        if (StringUtils.isBlank(from) || StringUtils.isBlank(to) || from.trim().equals(to.trim())) {
            return;
        }
        StoredSpec stored = requireStoredSpec(specId);
        if (StringUtils.isBlank(stored.getSourceContent())
                || !stored.getSourceContent().contains(EnvironmentScope.EXTENSION)) {
            return;
        }
        String content = jsonSource(stored.getSourceContent());
        String next = ManualSpecDocument.renameEnvironments(content, from, to);
        if (content.equals(next)) {
            return;
        }
        persistManualContent(stored, next, null, null);
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
        if (ManualSpecDocument.isManualContent(existing.getSourceContent())) {
            throw new IllegalArgumentException(
                    "This REST catalog is not backed by a Swagger file. Add APIs in Studio instead of updating a spec.");
        }
        Set<String> previousKeys = previousOperationKeys(specId, existing);

        String location = firstNonBlank(request == null ? null : request.location(), existing.getSourceLocation());
        String content = resolveReloadContent(request, existing, location);
        OpenAPI openApi = parseSpec(new LoadSpecRequest(location, content, null, null, null, null, null), content);
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
        String label = firstNonBlank(request == null ? null : request.label(), existing.getLabel());
        specs.put(specId, loadedSpec);
        studioStorage.saveSpec(toStoredSpec(loadedSpec,
                new LoadSpecRequest(location, content, request == null ? null : request.fileName(), baseUrl, name, label, null),
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
        studioStorage.listSpecs().forEach(stored -> {
            LoadedSpec loaded = specs.get(stored.getSpecId());
            summaries.put(stored.getSpecId(), loaded == null
                    ? toSummary(stored)
                    : toSummary(loaded, stored.getSourceLocation()));
        });
        return summaries.values().stream()
                .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(
                        StringUtils.defaultString(left.name(), left.title()),
                        StringUtils.defaultString(right.name(), right.title())))
                .toList();
    }

    public SpecSummary getSummary(String specId) {
        StoredSpec stored = studioStorage.findSpec(specId)
                .orElseThrow(() -> new NoSuchElementException("Spec not found: " + specId));
        LoadedSpec loaded = specs.get(specId);
        if (loaded != null) {
            return toSummary(loaded, stored.getSourceLocation());
        }
        return toSummary(stored);
    }

    public LoadedSpec requireSpec(String specId) {
        StoredSpec stored = studioStorage.findSpec(specId)
                .orElseThrow(() -> new NoSuchElementException("Spec not found: " + specId));
        LoadedSpec loaded = specs.get(specId);
        if (loaded != null) {
            return loaded;
        }
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
        stored.setLabel(normalizeLabel(request == null ? null : request.label()));
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
        return toSummary(spec, sourceLocation, labelOf(spec.getSpecId()));
    }

    private SpecSummary toSummary(LoadedSpec spec, String sourceLocation, String label) {
        List<String> tags = spec.getOperations().stream()
                .map(OperationPage::tag)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        return new SpecSummary(
                spec.getSpecId(),
                spec.getName(),
                normalizeLabel(label),
                spec.getTitle(),
                spec.getVersion(),
                spec.getDescription(),
                spec.getBaseUrl(),
                spec.getOperations().size(),
                tags,
                sourceLocation,
                serversOf(spec),
                isManualSpec(spec)
        );
    }

    private SpecSummary toSummary(LoadedSpec spec) {
        return toSummary(spec, sourceLocation(spec.getSpecId()));
    }

    private SpecSummary toSummary(StoredSpec spec) {
        return new SpecSummary(
                spec.getSpecId(),
                spec.getName(),
                normalizeLabel(spec.getLabel()),
                spec.getTitle(),
                spec.getVersion(),
                spec.getDescription(),
                spec.getBaseUrl(),
                spec.getOperationCount(),
                List.of(),
                spec.getSourceLocation(),
                List.of(),
                ManualSpecDocument.isManualContent(spec.getSourceContent())
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

    private String resolveUpdatedBaseUrl(StoredSpec stored, String requested) {
        String override = StringUtils.trimToNull(requested);
        try {
            LoadedSpec loaded = requireSpec(stored.getSpecId());
            return TestdataGenerator.resolveBaseUrl(loaded.getOpenApi(), override);
        } catch (RuntimeException exception) {
            return override;
        }
    }

    private void refreshLoaded(StoredSpec stored, boolean reinspect) {
        LoadedSpec loaded = specs.get(stored.getSpecId());
        if (loaded == null && !reinspect) {
            return;
        }
        try {
            LoadedSpec current = loaded != null ? loaded : restore(stored);
            List<OperationPage> operations = reinspect
                    ? TestdataGenerator.inspect(current.getOpenApi(), stored.getBaseUrl())
                    : current.getOperations();
            specs.put(stored.getSpecId(), new LoadedSpec(
                    stored.getSpecId(),
                    stored.getName(),
                    current.getOpenApi(),
                    operations,
                    current.getTitle(),
                    current.getVersion(),
                    current.getDescription(),
                    stored.getBaseUrl()
            ));
        } catch (RuntimeException exception) {
            specs.remove(stored.getSpecId());
        }
    }

    private void propagateCaseBaseUrls(String specId, String previousBaseUrl, String nextBaseUrl) {
        String previous = normalizeBaseUrl(previousBaseUrl);
        String next = StringUtils.trimToNull(nextBaseUrl);
        if (previous.equals(normalizeBaseUrl(next))) {
            return;
        }
        for (StudioTestCase testCase : studioStorage.listTestCases(specId, null, null)) {
            String current = testCase.getBaseUrl();
            if (StringUtils.isNotBlank(current) && !previous.equals(normalizeBaseUrl(current))) {
                continue;
            }
            testCase.setBaseUrl(next);
            studioStorage.saveTestCase(testCase);
        }
    }

    private static String normalizeBaseUrl(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        return value.trim().replaceAll("/+$", "");
    }

    private String labelOf(String specId) {
        return studioStorage.findSpec(specId)
                .map(StoredSpec::getLabel)
                .map(SpecCatalogService::normalizeLabel)
                .orElse(StoredSpec.DEFAULT_LABEL);
    }

    private static String normalizeLabel(String label) {
        if (StringUtils.isBlank(label)) {
            return StoredSpec.DEFAULT_LABEL;
        }
        return label.trim();
    }

    private static List<SpecServer> serversOf(LoadedSpec spec) {
        if (spec.getOperations().isEmpty() || spec.getOperations().get(0).servers() == null) {
            return List.of();
        }
        return spec.getOperations().get(0).servers();
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

    private StoredSpec requireStoredSpec(String specId) {
        return studioStorage.findSpec(specId)
                .orElseThrow(() -> new NoSuchElementException("Spec not found: " + specId));
    }

    private String jsonSource(String content) {
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("Spec source is missing");
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return content;
        }
        try {
            OpenAPI openApi = TestdataGenerator.loadSpecFromContent(content);
            return io.swagger.v3.core.util.Json.pretty(openApi);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Spec source must be OpenAPI JSON or YAML to add an API", exception);
        }
    }

    private OperationPage persistManualContent(StoredSpec stored, String content, String path, String method) {
        OpenAPI openApi = TestdataGenerator.loadSpecFromContent(content);
        List<OperationPage> operations = TestdataGenerator.inspect(openApi, stored.getBaseUrl());
        LoadedSpec loadedSpec = new LoadedSpec(
                stored.getSpecId(),
                stored.getName(),
                openApi,
                operations,
                TestdataGenerator.specTitle(openApi),
                TestdataGenerator.specVersion(openApi),
                TestdataGenerator.specDescription(openApi),
                stored.getBaseUrl()
        );
        specs.put(stored.getSpecId(), loadedSpec);
        stored.setSourceContent(content);
        stored.setTitle(loadedSpec.getTitle());
        stored.setVersion(loadedSpec.getVersion());
        stored.setDescription(loadedSpec.getDescription());
        stored.setOperationCount(operations.size());
        studioStorage.saveSpec(stored);
        if (StringUtils.isBlank(path) || StringUtils.isBlank(method)) {
            return null;
        }
        String expectedMethod = method.trim().toUpperCase(Locale.ROOT);
        String expectedPath = ManualSpecDocument.normalizePath(path);
        return operations.stream()
                .filter(operation -> expectedMethod.equalsIgnoreCase(operation.method())
                        && (expectedPath.equals(operation.path()) || expectedPath.equals(operation.rawPath())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Added REST API was not found after save"));
    }

    private boolean isManualSpec(LoadedSpec spec) {
        if (ManualSpecDocument.isManual(spec.getOpenApi())) {
            return true;
        }
        return studioStorage.findSpec(spec.getSpecId())
                .map(StoredSpec::getSourceContent)
                .map(ManualSpecDocument::isManualContent)
                .orElse(false);
    }
}
