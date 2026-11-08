package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.dto.CollectionVariables;
import com.tx.api.test.generator.studio.dto.GlobalVariables;
import com.tx.api.test.generator.studio.service.TestDataStudioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/variables")
public class VariablesController {

    private final TestDataStudioService testDataStudioService;

    public VariablesController(TestDataStudioService testDataStudioService) {
        this.testDataStudioService = testDataStudioService;
    }

    @GetMapping("/globals")
    public GlobalVariables globals() {
        return testDataStudioService.getGlobalVariables();
    }

    @PutMapping("/globals")
    public GlobalVariables saveGlobals(@RequestBody(required = false) GlobalVariables request) {
        return testDataStudioService.saveGlobalVariables(request);
    }

    @GetMapping("/collections/{specId}")
    public CollectionVariables collection(@PathVariable String specId) {
        return testDataStudioService.getCollectionVariables(specId);
    }

    @GetMapping("/collections/{specId}/shared-settings")
    public com.tx.api.test.generator.studio.collection.SharedSettings sharedSettings(@PathVariable String specId) {
        return testDataStudioService.getSharedSettings(specId);
    }

    @PutMapping("/collections/{specId}/shared-settings")
    public com.tx.api.test.generator.studio.collection.SharedSettings saveSharedSettings(
            @PathVariable String specId,
            @RequestBody(required = false) com.tx.api.test.generator.studio.collection.SharedSettings request) {
        return testDataStudioService.saveSharedSettings(specId, request);
    }

    @PutMapping("/collections/{specId}")
    public CollectionVariables saveCollection(@PathVariable String specId,
                                              @RequestBody(required = false) CollectionVariables request) {
        return testDataStudioService.saveCollectionVariables(specId, request);
    }
}
