package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.catalog.OperationSummary;
import com.tx.api.test.generator.studio.dto.EnvironmentImportResult;
import com.tx.api.test.generator.studio.dto.EnvironmentSummary;
import com.tx.api.test.generator.studio.dto.SaveEnvironmentRequest;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.ManualOperationRequest;
import com.tx.api.test.generator.studio.dto.ReloadSpecResult;
import com.tx.api.test.generator.studio.dto.SaveSpecRequest;
import com.tx.api.test.generator.studio.dto.SpecListPage;
import com.tx.api.test.generator.studio.dto.SpecSummary;
import com.tx.api.test.generator.studio.storage.SpecListFilter;
import com.tx.api.test.generator.studio.service.SpecCatalogService;
import com.tx.api.test.generator.studio.service.TestDataStudioService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/specs")
public class SpecController {

    private final SpecCatalogService specCatalogService;
    private final TestDataStudioService testDataStudioService;

    public SpecController(SpecCatalogService specCatalogService, TestDataStudioService testDataStudioService) {
        this.specCatalogService = specCatalogService;
        this.testDataStudioService = testDataStudioService;
    }

    @PostMapping
    public SpecSummary load(@RequestBody LoadSpecRequest request) {
        return specCatalogService.load(request);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SpecSummary upload(@RequestPart("file") MultipartFile file,
                              @RequestParam(value = "name", required = false) String name,
                              @RequestParam(value = "label", required = false) String label,
                              @RequestParam(value = "baseUrl", required = false) String baseUrl) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return specCatalogService.load(new LoadSpecRequest(null, content, file.getOriginalFilename(), baseUrl, name, label, null, null, null));
    }

    @PutMapping("/{specId}")
    public SpecSummary update(@PathVariable String specId, @RequestBody SaveSpecRequest request) {
        return specCatalogService.rename(specId, request);
    }

    @DeleteMapping("/{specId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String specId) {
        specCatalogService.delete(specId);
    }

    @PostMapping("/{specId}/reload")
    public ReloadSpecResult reload(@PathVariable String specId,
                                   @RequestBody(required = false) LoadSpecRequest request) {
        return specCatalogService.reload(specId, request);
    }

    @PostMapping(value = "/{specId}/reload/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReloadSpecResult reloadUpload(@PathVariable String specId,
                                         @RequestPart("file") MultipartFile file,
                                         @RequestParam(value = "baseUrl", required = false) String baseUrl,
                                         @RequestParam(value = "expectedUpdatedAt", required = false) String expectedUpdatedAt,
                                         @RequestParam(value = "forceOverwrite", required = false) Boolean forceOverwrite)
            throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return specCatalogService.reload(specId, new LoadSpecRequest(null, content, file.getOriginalFilename(), baseUrl,
                null, null, null, expectedUpdatedAt, forceOverwrite));
    }

    @GetMapping
    public Object list(@RequestParam(value = "q", required = false) String q,
                       @RequestParam(value = "label", required = false) String label,
                       @RequestParam(value = "sort", required = false) String sort,
                       @RequestParam(value = "limit", required = false) Integer limit,
                       @RequestParam(value = "offset", required = false) Integer offset,
                       @RequestParam(value = "ids", required = false) String ids,
                       @RequestParam(value = "manual", required = false) Boolean manual) {
        SpecListFilter filter = SpecListFilter.of(q, label, sort, offset, limit, ids, manual);
        if (!filter.paged()) {
            return specCatalogService.list();
        }
        return specCatalogService.listPage(filter);
    }

    @GetMapping("/{specId}")
    public SpecSummary get(@PathVariable String specId) {
        return specCatalogService.getSummary(specId);
    }

    @GetMapping("/{specId}/operations")
    public List<OperationSummary> operations(@PathVariable String specId) {
        return specCatalogService.listOperations(specId);
    }

    @GetMapping("/{specId}/operations/{operationKey}")
    public OperationPage operation(@PathVariable String specId, @PathVariable String operationKey) {
        return specCatalogService.getOperation(specId, operationKey);
    }

    @PostMapping("/{specId}/operations")
    @ResponseStatus(HttpStatus.CREATED)
    public OperationPage addOperation(@PathVariable String specId, @RequestBody ManualOperationRequest request) {
        return specCatalogService.addOperation(specId, request);
    }

    @DeleteMapping("/{specId}/operations/{operationKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOperation(@PathVariable String specId, @PathVariable String operationKey) {
        specCatalogService.deleteOperation(specId, operationKey);
    }

    @GetMapping("/{specId}/environments")
    public List<EnvironmentSummary> environments(@PathVariable String specId) {
        return testDataStudioService.listEnvironments(specId);
    }

    @GetMapping("/{specId}/environments/{environmentId}")
    public EnvironmentSummary environment(@PathVariable String specId, @PathVariable String environmentId) {
        return testDataStudioService.getEnvironment(specId, environmentId);
    }

    @PostMapping("/{specId}/environments")
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentSummary createEnvironment(@PathVariable String specId,
                                                @RequestBody SaveEnvironmentRequest request) {
        return testDataStudioService.createEnvironment(specId, request);
    }

    @PutMapping("/{specId}/environments/{environmentId}")
    public EnvironmentSummary updateEnvironment(@PathVariable String specId,
                                                @PathVariable String environmentId,
                                                @RequestBody SaveEnvironmentRequest request) {
        return testDataStudioService.updateEnvironment(specId, environmentId, request);
    }

    @PostMapping("/{specId}/environments/{environmentId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentSummary duplicateEnvironment(@PathVariable String specId,
                                                   @PathVariable String environmentId) {
        return testDataStudioService.duplicateEnvironment(specId, environmentId);
    }

    @PostMapping("/{specId}/environments/{environmentId}/apply")
    public EnvironmentImportResult applyEnvironment(@PathVariable String specId,
                                                    @PathVariable String environmentId) {
        return testDataStudioService.applyEnvironment(specId, environmentId);
    }

    @DeleteMapping("/{specId}/environments/{environmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEnvironment(@PathVariable String specId, @PathVariable String environmentId) {
        testDataStudioService.deleteEnvironment(specId, environmentId);
    }
}
