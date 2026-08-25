package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.catalog.OperationPage;
import com.tx.api.test.generator.catalog.OperationSummary;
import com.tx.api.test.generator.studio.dto.LoadSpecRequest;
import com.tx.api.test.generator.studio.dto.SpecSummary;
import com.tx.api.test.generator.studio.service.SpecCatalogService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/specs")
public class SpecController {

    private final SpecCatalogService specCatalogService;

    public SpecController(SpecCatalogService specCatalogService) {
        this.specCatalogService = specCatalogService;
    }

    @PostMapping
    public SpecSummary load(@RequestBody LoadSpecRequest request) {
        return specCatalogService.load(request);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SpecSummary upload(@RequestPart("file") MultipartFile file,
                              @RequestParam(value = "baseUrl", required = false) String baseUrl) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return specCatalogService.load(new LoadSpecRequest(null, content, file.getOriginalFilename(), baseUrl));
    }

    @GetMapping
    public List<SpecSummary> list() {
        return specCatalogService.list();
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
}
