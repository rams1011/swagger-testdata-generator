package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.dto.GlobalVariables;
import com.tx.api.test.generator.studio.service.TestDataStudioService;
import org.springframework.web.bind.annotation.GetMapping;
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
}
