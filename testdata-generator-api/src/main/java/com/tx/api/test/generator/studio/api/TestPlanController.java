package com.tx.api.test.generator.studio.api;

import com.tx.api.test.generator.studio.dto.SaveTestPlanRequest;
import com.tx.api.test.generator.studio.dto.TestPlanView;
import com.tx.api.test.generator.studio.service.TestDataStudioService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/specs/{specId}/plans")
public class TestPlanController {

    private final TestDataStudioService testDataStudioService;

    public TestPlanController(TestDataStudioService testDataStudioService) {
        this.testDataStudioService = testDataStudioService;
    }

    @GetMapping
    public List<TestPlanView> list(@PathVariable String specId) {
        return testDataStudioService.listPlans(specId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestPlanView create(@PathVariable String specId, @RequestBody SaveTestPlanRequest request) {
        return testDataStudioService.createPlan(specId, request);
    }

    @PutMapping("/{planId}")
    public TestPlanView update(@PathVariable String specId,
                               @PathVariable String planId,
                               @RequestBody SaveTestPlanRequest request) {
        return testDataStudioService.updatePlan(specId, planId, request);
    }

    @DeleteMapping("/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String specId, @PathVariable String planId) {
        testDataStudioService.deletePlan(specId, planId);
    }
}
