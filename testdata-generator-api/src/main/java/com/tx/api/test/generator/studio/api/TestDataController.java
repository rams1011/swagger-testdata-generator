package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.dto.CollectionExport;
import com.tx.api.test.generator.studio.dto.CollectionImportResult;
import com.tx.api.test.generator.studio.dto.CopyEnvironmentOverlayRequest;
import com.tx.api.test.generator.studio.dto.EnvironmentExport;
import com.tx.api.test.generator.studio.dto.EnvironmentImportResult;
import com.tx.api.test.generator.studio.dto.ExcelExport;
import com.tx.api.test.generator.studio.dto.ExportEnvironmentRequest;
import com.tx.api.test.generator.studio.dto.ExportRequest;
import com.tx.api.test.generator.studio.dto.RunAuth;
import com.tx.api.test.generator.studio.dto.RunPlanRequest;
import com.tx.api.test.generator.studio.dto.RunPlanResult;
import com.tx.api.test.generator.studio.dto.RunTestRequest;
import com.tx.api.test.generator.studio.dto.RunTestResult;
import com.tx.api.test.generator.studio.dto.SaveTestDataRequest;
import com.tx.api.test.generator.studio.dto.TestCaseView;
import com.tx.api.test.generator.studio.service.TestDataStudioService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.Map;

@RestController
@RequestMapping("/api/testdata")
public class TestDataController {

    private final TestDataStudioService testDataStudioService;

    public TestDataController(TestDataStudioService testDataStudioService) {
        this.testDataStudioService = testDataStudioService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestCaseView create(@RequestBody SaveTestDataRequest request) {
        return testDataStudioService.create(request);
    }

    @PostMapping("/run")
    public RunTestResult run(@RequestBody RunTestRequest request) {
        return testDataStudioService.run(request);
    }

    @PostMapping("/run-plan")
    public RunPlanResult runPlan(@RequestBody RunPlanRequest request) {
        return testDataStudioService.runPlan(request);
    }

    @PostMapping("/{id}/run")
    public RunTestResult runSaved(@PathVariable String id, @RequestBody(required = false) RunTestRequest request) {
        RunAuth auth = request == null ? null : request.auth();
        String environmentId = request == null ? null : request.environmentId();
        return testDataStudioService.run(new RunTestRequest(id, null, auth, environmentId));
    }

    @PutMapping("/{id}")
    public TestCaseView update(@PathVariable String id, @RequestBody SaveTestDataRequest request) {
        return testDataStudioService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestParam(required = false) String environmentId) {
        testDataStudioService.delete(id, environmentId);
    }

    @GetMapping("/config")
    public Map<String, String> config() {
        return testDataStudioService.runtimeConfig();
    }

    @GetMapping("/{id}")
    public TestCaseView get(@PathVariable String id, @RequestParam(required = false) String environmentId) {
        return testDataStudioService.get(id, environmentId);
    }

    @GetMapping
    public List<TestCaseView> list(@RequestParam(required = false) String specId,
                                   @RequestParam(required = false) String planId,
                                   @RequestParam(required = false) String operationKey,
                                   @RequestParam(required = false) String environmentId) {
        return testDataStudioService.list(specId, planId, operationKey, environmentId);
    }

    @PostMapping("/{id}/duplicate")
    public TestCaseView duplicate(@PathVariable String id, @RequestBody CopyEnvironmentOverlayRequest request) {
        return testDataStudioService.duplicate(id, request);
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody ExportRequest request) {
        ExcelExport exported = testDataStudioService.export(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(exported.mediaType()));
        headers.setContentLength(exported.content().length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(exported.fileName(), StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<>(exported.content(), headers, HttpStatus.OK);
    }

    @PostMapping("/export-collection")
    public ResponseEntity<byte[]> exportCollection(@RequestBody ExportRequest request) {
        CollectionExport exported = testDataStudioService.exportCollection(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(exported.mediaType()));
        headers.setContentLength(exported.content().length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(exported.fileName(), StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<>(exported.content(), headers, HttpStatus.OK);
    }

    @PostMapping(value = "/import-collection", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CollectionImportResult importCollectionJson(@RequestParam(required = false) String specId,
                                                       @RequestParam(required = false) String environmentId,
                                                       @RequestBody String json) {
        return testDataStudioService.importCollection(
                json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8),
                "collection.json",
                specId,
                environmentId);
    }

    @PostMapping(value = "/import-collection", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CollectionImportResult importCollectionFile(@RequestParam(required = false) String specId,
                                                       @RequestParam(required = false) String environmentId,
                                                       @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Collection JSON or zip is required");
        }
        return testDataStudioService.importCollection(file.getBytes(), file.getOriginalFilename(), specId,
                environmentId);
    }

    @PostMapping("/export-environment")
    public ResponseEntity<byte[]> exportEnvironment(@RequestBody ExportEnvironmentRequest request) {
        EnvironmentExport exported = testDataStudioService.exportEnvironment(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(exported.mediaType()));
        headers.setContentLength(exported.content().length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(exported.fileName(), StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<>(exported.content(), headers, HttpStatus.OK);
    }

    @PostMapping(value = "/import-environment", consumes = MediaType.APPLICATION_JSON_VALUE)
    public EnvironmentImportResult importEnvironmentJson(@RequestParam String specId,
                                                         @RequestParam(defaultValue = "true") boolean apply,
                                                         @RequestBody String json) {
        return testDataStudioService.importEnvironment(
                json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8), specId, apply);
    }

    @PostMapping(value = "/import-environment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EnvironmentImportResult importEnvironmentFile(@RequestParam String specId,
                                                         @RequestParam(defaultValue = "true") boolean apply,
                                                         @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Environment JSON is required");
        }
        return testDataStudioService.importEnvironment(file.getBytes(), specId, apply);
    }
}
