package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.dto.ExcelExport;
import com.tx.api.test.generator.studio.dto.ExportRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/testdata")
public class TestDataController {

    private static final MediaType EXCEL = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final TestDataStudioService testDataStudioService;

    public TestDataController(TestDataStudioService testDataStudioService) {
        this.testDataStudioService = testDataStudioService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestCaseView create(@RequestBody SaveTestDataRequest request) {
        return testDataStudioService.create(request);
    }

    @PutMapping("/{id}")
    public TestCaseView update(@PathVariable String id, @RequestBody SaveTestDataRequest request) {
        return testDataStudioService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        testDataStudioService.delete(id);
    }

    @GetMapping("/config")
    public Map<String, String> config() {
        return testDataStudioService.runtimeConfig();
    }

    @GetMapping("/{id}")
    public TestCaseView get(@PathVariable String id) {
        return testDataStudioService.get(id);
    }

    @GetMapping
    public List<TestCaseView> list(@RequestParam(required = false) String specId,
                                   @RequestParam(required = false) String planId,
                                   @RequestParam(required = false) String operationKey) {
        return testDataStudioService.list(specId, planId, operationKey);
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody ExportRequest request) {
        ExcelExport exported = testDataStudioService.export(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(EXCEL);
        headers.setContentLength(exported.content().length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(exported.fileName(), StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<>(exported.content(), headers, HttpStatus.OK);
    }
}
